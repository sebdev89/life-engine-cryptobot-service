package io.lifeengine.cryptobot.application.oracle;

import io.lifeengine.cryptobot.adapters.marketdata.MarketDataProperties;
import io.lifeengine.cryptobot.adapters.marketdata.PriceProvider;
import io.lifeengine.cryptobot.adapters.marketdata.PriceSource;
import io.lifeengine.cryptobot.adapters.marketdata.TokenRegistry;
import io.lifeengine.cryptobot.application.chaos.PriceChaos;
import io.lifeengine.cryptobot.core.oracle.OracleConsensus;
import io.lifeengine.cryptobot.core.oracle.OracleLimits;
import io.lifeengine.cryptobot.core.oracle.OracleReading;
import io.lifeengine.cryptobot.core.oracle.OracleRefusal;
import io.lifeengine.cryptobot.core.oracle.PriceObservation;
import io.lifeengine.cryptobot.core.oracle.PriceOracle;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * The multi-source price oracle (paper §22): asks every enabled {@link PriceSource} for
 * the same assets, concurrently, and reduces what comes back with the pure {@link PriceOracle}
 * under the configured {@link OracleLimits}. Same shape as {@code CachedArsQuotesService}:
 * one slow source never delays the others beyond its own timeout, and a source that fails is an
 * absent observation, never an error.
 *
 * <p>Two consumers:
 * <ul>
 *   <li>{@link #read(Collection)} — the policy: an {@link OracleReading} that says, per asset,
 *       whether a consensus exists and from which quotes. Refused ⇒ the policy denies.
 *   <li>{@link #prices(Set)} — the portfolio view ({@link PriceProvider}): the accepted median,
 *       labelled {@code oracle:<sources>}; with no consensus the static fallback price if there is
 *       one, labelled {@code fallback-static}, so the screen still shows a portfolio — the policy
 *       will refuse to trade on it.
 * </ul>
 *
 * <p>The circuit breaker compares against the last <em>accepted</em> consensus per asset kept
 * in this process; it is not persisted (post-MVP), so a restart starts without a reference.
 * Metrics: {@code cryptobot_oracle_source_fetch_total{source,ok}},
 * {@code cryptobot_oracle_source_latency_seconds{source}},
 * {@code cryptobot_oracle_consensus_total{asset,result}}.
 */
@Service
public class PriceOracleService implements PriceProvider {

    private static final Logger log = LoggerFactory.getLogger(PriceOracleService.class);
    public static final String METRIC_SOURCE_FETCH = "cryptobot.oracle.source.fetch";
    public static final String METRIC_SOURCE_LATENCY = "cryptobot.oracle.source.latency";
    public static final String METRIC_CONSENSUS = "cryptobot.oracle.consensus";
    public static final String SOURCE_FALLBACK = "fallback-static";
    public static final String SOURCE_ORACLE_PREFIX = "oracle:";

    private final List<PriceSource> sources;
    private final TokenRegistry registry;
    private final MarketDataProperties properties;
    private final OracleLimits limits;
    private final MeterRegistry meters;
    private final java.time.Clock clock;
    /** the demo's price tampering; {@code null} outside the demo stack (no bean, nothing consulted). */
    private final PriceChaos chaos;
    private final Map<String, OracleConsensus> lastAccepted = new ConcurrentHashMap<>();

    @Autowired
    public PriceOracleService(List<PriceSource> sources, TokenRegistry registry, MarketDataProperties properties, MeterRegistry meters,
            Optional<PriceChaos> chaos) {
        this(sources, registry, properties, meters, java.time.Clock.systemUTC(), chaos.orElse(null));
    }

    public PriceOracleService(List<PriceSource> sources, TokenRegistry registry, MarketDataProperties properties, MeterRegistry meters,
            java.time.Clock clock) {
        this(sources, registry, properties, meters, clock, null);
    }

    public PriceOracleService(List<PriceSource> sources, TokenRegistry registry, MarketDataProperties properties, MeterRegistry meters,
            java.time.Clock clock, PriceChaos chaos) {
        this.sources = List.copyOf(sources);
        this.registry = registry;
        this.properties = properties;
        this.limits = properties.oracle().limits(); // throws ⇒ the service does not start without valid limits
        this.meters = meters;
        this.clock = clock;
        this.chaos = chaos;
    }

    /** The integrity assumptions in force in this process. */
    public OracleLimits limits() {
        return limits;
    }

    public List<String> sourceIds() {
        return sources.stream().filter(PriceSource::enabled).map(PriceSource::id).toList();
    }

    /** A reading for the symbols the policy speaks. A symbol the registry does not know is read as {@code NO_OBSERVATIONS}. */
    public Mono<OracleReading> read(Collection<String> symbols) {
        Map<String, String> symbolByMint = new LinkedHashMap<>();
        List<String> unknown = new ArrayList<>();
        for (String raw : symbols) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            String symbol = raw.trim().toUpperCase(Locale.ROOT);
            Optional<String> mint = registry.mintOfSymbol(symbol);
            if (mint.isPresent()) {
                symbolByMint.put(mint.get(), symbol);
            } else if (!unknown.contains(symbol)) {
                unknown.add(symbol);
            }
        }
        return observe(symbolByMint, unknown);
    }

    /** A reading for wallet mints (devnet aliases are priced as their mainnet mint). */
    public Mono<OracleReading> readMints(Collection<String> mints) {
        Map<String, String> symbolByMint = new LinkedHashMap<>();
        for (String mint : mints) {
            registry.priceMintOf(mint).ifPresent(price -> symbolByMint.put(price, registry.symbolOf(price)));
        }
        return observe(symbolByMint, List.of());
    }

    @Override
    public Mono<Map<String, PriceQuote>> prices(Set<String> mainnetMints) {
        if (mainnetMints == null || mainnetMints.isEmpty()) {
            return Mono.just(Map.of());
        }
        return readMints(mainnetMints).map(reading -> {
            Map<String, PriceQuote> out = new LinkedHashMap<>();
            for (String mint : mainnetMints) {
                String symbol = registry.symbolOf(mint);
                Optional<OracleConsensus> c = reading.of(symbol);
                if (c.isPresent() && c.get().accepted()) {
                    out.put(mint, new PriceQuote(mint, c.get().priceUsd(), SOURCE_ORACLE_PREFIX + String.join("+", c.get().sources()), c.get().asOf(), null));
                    continue;
                }
                BigDecimal fallback = properties.fallbackPrices().get(symbol);
                if (fallback == null) {
                    fallback = properties.fallbackPrices().get(symbol.toUpperCase(Locale.ROOT));
                }
                if (fallback != null) {
                    out.put(mint, new PriceQuote(mint, fallback, SOURCE_FALLBACK, reading.readAt(), null));
                }
            }
            return out;
        });
    }

    private Mono<OracleReading> observe(Map<String, String> symbolByMint, List<String> unknownSymbols) {
        if (symbolByMint.isEmpty()) {
            Instant now = clock.instant();
            return Mono.just(new OracleReading(now, limits, unknownSymbols.stream().map(s -> consensus(s, null, List.of(), now)).toList()));
        }
        Map<String, String> asked = Map.copyOf(symbolByMint);
        return Flux.fromIterable(sources)
                .filter(PriceSource::enabled)
                .flatMap(source -> fetch(source, asked))
                .collectList()
                .map(perSource -> {
                    Instant now = clock.instant();
                    List<PriceObservation> all = new ArrayList<>();
                    perSource.forEach(all::addAll);
                    List<OracleConsensus> assets = new ArrayList<>();
                    for (Map.Entry<String, String> e : asked.entrySet()) {
                        String symbol = e.getValue();
                        List<PriceObservation> mine = all.stream().filter(o -> o.asset().equals(symbol)).toList();
                        if (chaos != null && chaos.armed()) {
                            // an internal ticket, demo only: the adversarial price is injected into what the sources said, and logged.
                            List<PriceObservation> tampered = chaos.apply(symbol, e.getKey(), mine, sourceIds(), now);
                            if (tampered != mine) {
                                log.warn("oracle_price_injected — DEMO ONLY asset={} real={} injected={}", symbol,
                                        mine.stream().map(o -> o.source() + "=" + o.priceUsd()).toList(),
                                        tampered.stream().map(o -> o.source() + "=" + o.priceUsd() + "@" + o.observedAt()).toList());
                                mine = tampered;
                            }
                        }
                        assets.add(consensus(symbol, e.getKey(), mine, now));
                    }
                    unknownSymbols.forEach(s -> assets.add(consensus(s, null, List.of(), now)));
                    return new OracleReading(now, limits, assets);
                });
    }

    private OracleConsensus consensus(String symbol, String mint, List<PriceObservation> observations, Instant now) {
        OracleConsensus c = PriceOracle.consensus(symbol, mint, observations, lastAccepted.get(symbol), now, limits);
        if (c.accepted()) {
            lastAccepted.put(symbol, c);
            meters.counter(METRIC_CONSENSUS, "asset", symbol, "result", "accepted").increment();
        } else {
            for (OracleRefusal r : c.refusals()) {
                meters.counter(METRIC_CONSENSUS, "asset", symbol, "result", r.name().toLowerCase(Locale.ROOT)).increment();
            }
            log.warn("oracle_refused asset={} refusals={} problems={} sources={}", symbol, c.refusals(), c.problems(), c.sources());
        }
        return c;
    }

    private Mono<List<PriceObservation>> fetch(PriceSource source, Map<String, String> asked) {
        Timer.Sample sample = Timer.start(meters);
        return source.observe(asked)
                .defaultIfEmpty(List.of())
                .doOnSuccess(list -> record(source.id(), true, sample))
                .onErrorResume(ex -> {
                    record(source.id(), false, sample);
                    log.warn("oracle_source_failed source={} error={}", source.id(), ex.toString());
                    return Mono.just(List.of());
                });
    }

    private void record(String source, boolean ok, Timer.Sample sample) {
        meters.counter(METRIC_SOURCE_FETCH, "source", source, "ok", Boolean.toString(ok)).increment();
        sample.stop(Timer.builder(METRIC_SOURCE_LATENCY).tag("source", source).register(meters));
    }
}
