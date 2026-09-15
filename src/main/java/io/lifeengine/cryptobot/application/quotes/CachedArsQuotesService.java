package io.lifeengine.cryptobot.application.quotes;

import io.lifeengine.cryptobot.adapters.quotes.ExchangeQuoteSource;
import io.lifeengine.cryptobot.adapters.quotes.QuotesProperties;
import io.lifeengine.cryptobot.domain.quotes.ArsQuote;
import io.lifeengine.cryptobot.domain.quotes.QuoteBoard;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * {@link ArsQuotesPort} over every {@link ExchangeQuoteSource} bean, with one in-memory board per
 * exchange refreshed at most every {@code cryptobot.quotes.cache-ttl} (KAN-355).
 *
 * <p>Failure policy per exchange: a refresh that fails serves the previous board flagged
 * {@code stale} while it is younger than {@code stale-max}; after that the exchange is reported
 * {@code FETCH_FAILED}. Exchanges are fetched concurrently and one slow exchange never delays the
 * others beyond its own timeout. Metrics: {@code cryptobot_quotes_fetch_total{exchange,ok}} and
 * {@code cryptobot_quotes_fetch_latency_seconds{exchange}} (KAN-353 dashboard).
 */
@Service
public class CachedArsQuotesService implements ArsQuotesPort {

    private static final Logger log = LoggerFactory.getLogger(CachedArsQuotesService.class);
    public static final String METRIC_FETCH = "cryptobot.quotes.fetch";
    public static final String METRIC_LATENCY = "cryptobot.quotes.fetch.latency";

    private record Cached(List<ArsQuote> quotes, Instant fetchedAt) {}

    /** One exchange's contribution to a board: either quotes (maybe stale) or a reason. */
    private record ExchangeResult(String exchange, List<ArsQuote> quotes, QuoteBoard.Unavailable unavailable) {}

    private final List<ExchangeQuoteSource> sources;
    private final QuotesProperties properties;
    private final MeterRegistry meters;
    private final Clock clock;
    private final Map<String, Cached> lastGood = new ConcurrentHashMap<>();
    private final Map<String, Mono<Cached>> inFlight = new ConcurrentHashMap<>();

    @Autowired
    public CachedArsQuotesService(List<ExchangeQuoteSource> sources, QuotesProperties properties, MeterRegistry meters) {
        this(sources, properties, meters, Clock.systemUTC());
    }

    public CachedArsQuotesService(List<ExchangeQuoteSource> sources, QuotesProperties properties, MeterRegistry meters, Clock clock) {
        this.sources = List.copyOf(sources);
        this.properties = properties;
        this.meters = meters;
        this.clock = clock;
    }

    @Override
    public Mono<QuoteBoard> board(String asset) {
        String wanted = asset.trim().toUpperCase(Locale.ROOT);
        return Flux.fromIterable(sources)
                .flatMap(this::exchangeBoard)
                .collectList()
                .map(results -> assemble(wanted, results));
    }

    @Override
    public List<String> exchanges() {
        return sources.stream().map(ExchangeQuoteSource::exchange).toList();
    }

    private QuoteBoard assemble(String asset, List<ExchangeResult> results) {
        List<ArsQuote> quotes = new ArrayList<>();
        List<QuoteBoard.Unavailable> unavailable = new ArrayList<>();
        // Deterministic order regardless of which exchange answered first.
        for (ExchangeQuoteSource source : sources) {
            ExchangeResult r = results.stream().filter(x -> x.exchange().equals(source.exchange())).findFirst().orElse(null);
            if (r == null) {
                continue;
            }
            if (r.unavailable() != null) {
                unavailable.add(r.unavailable());
                continue;
            }
            Optional<ArsQuote> q = r.quotes().stream().filter(x -> x.asset().equals(asset)).findFirst();
            if (q.isPresent()) {
                quotes.add(q.get());
            } else {
                unavailable.add(new QuoteBoard.Unavailable(r.exchange(), QuoteBoard.Unavailable.Reason.NOT_LISTED, asset + "/ARS not listed"));
            }
        }
        return new QuoteBoard(asset, quotes, unavailable, clock.instant());
    }

    private Mono<ExchangeResult> exchangeBoard(ExchangeQuoteSource source) {
        String id = source.exchange();
        if (!source.enabled()) {
            return Mono.just(new ExchangeResult(id, List.of(), new QuoteBoard.Unavailable(id, QuoteBoard.Unavailable.Reason.DISABLED, "disabled by configuration")));
        }
        Instant now = clock.instant();
        Cached cached = lastGood.get(id);
        if (cached != null && isYoungerThan(cached, properties.cacheTtl(), now)) {
            return Mono.just(new ExchangeResult(id, cached.quotes(), null));
        }
        return refresh(source)
                .map(c -> new ExchangeResult(id, c.quotes(), null))
                .onErrorResume(ex -> {
                    Cached previous = lastGood.get(id);
                    if (previous != null && isYoungerThan(previous, properties.staleMax(), clock.instant())) {
                        log.warn("quotes_fetch_failed_serving_stale exchange={} fetchedAt={} error={}", id, previous.fetchedAt(), ex.toString());
                        return Mono.just(new ExchangeResult(id, previous.quotes().stream().map(ArsQuote::asStale).toList(), null));
                    }
                    log.warn("quotes_fetch_failed exchange={} error={}", id, ex.toString());
                    return Mono.just(new ExchangeResult(id, List.of(),
                            new QuoteBoard.Unavailable(id, QuoteBoard.Unavailable.Reason.FETCH_FAILED, summarize(ex))));
                });
    }

    /** Single in-flight refresh per exchange: concurrent callers share the same request. */
    private Mono<Cached> refresh(ExchangeQuoteSource source) {
        String id = source.exchange();
        return inFlight.computeIfAbsent(id, k -> {
            Timer.Sample sample = Timer.start(meters);
            return source.fetchArsQuotes()
                    .map(quotes -> new Cached(quotes, clock.instant()))
                    .doOnSuccess(c -> {
                        lastGood.put(id, c);
                        record(id, true, sample);
                    })
                    .doOnError(ex -> record(id, false, sample))
                    .doFinally(s -> inFlight.remove(id))
                    .cache();
        });
    }

    private void record(String exchange, boolean ok, Timer.Sample sample) {
        meters.counter(METRIC_FETCH, "exchange", exchange, "ok", Boolean.toString(ok)).increment();
        sample.stop(Timer.builder(METRIC_LATENCY).tag("exchange", exchange).register(meters));
    }

    private static boolean isYoungerThan(Cached cached, java.time.Duration maxAge, Instant now) {
        return !cached.fetchedAt().plus(maxAge).isBefore(now);
    }

    private static String summarize(Throwable ex) {
        String msg = ex.getMessage();
        String base = ex.getClass().getSimpleName();
        if (msg == null || msg.isBlank()) {
            return base;
        }
        // Keep it short and free of upstream bodies: enough to diagnose, nothing to leak.
        String trimmed = msg.length() > 120 ? msg.substring(0, 120) + "…" : msg;
        return base + ": " + trimmed;
    }
}
