package io.lifeengine.cryptobot.application.oracle;

import static org.assertj.core.api.Assertions.assertThat;

import io.lifeengine.cryptobot.adapters.marketdata.MarketDataProperties;
import io.lifeengine.cryptobot.adapters.marketdata.PriceProvider;
import io.lifeengine.cryptobot.adapters.marketdata.PriceSource;
import io.lifeengine.cryptobot.adapters.marketdata.TokenRegistry;
import io.lifeengine.cryptobot.domain.oracle.OracleReading;
import io.lifeengine.cryptobot.domain.oracle.OracleRefusal;
import io.lifeengine.cryptobot.domain.oracle.PriceObservation;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class PriceOracleServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-18T12:00:00Z");
    private static final MarketDataProperties PROPS = new MarketDataProperties(null, null, Map.of(), Map.of("SOL", new BigDecimal("100"), "USDC", BigDecimal.ONE),
            false, null, null, new MarketDataProperties.Oracle(2, Duration.ofSeconds(60), 100, 1_000, Duration.ofMinutes(5)));
    private static final TokenRegistry REGISTRY = new TokenRegistry(PROPS);

    /** A source that answers a fixed price per symbol (or nothing, or an error). */
    static PriceSource source(String id, Map<String, String> prices, boolean enabled, boolean fails) {
        return new PriceSource() {
            @Override public String id() { return id; }
            @Override public boolean enabled() { return enabled; }
            @Override public Mono<List<PriceObservation>> observe(Map<String, String> symbolByMint) {
                if (fails) {
                    return Mono.error(new IllegalStateException("boom"));
                }
                return Mono.just(symbolByMint.entrySet().stream()
                        .filter(e -> prices.containsKey(e.getValue()))
                        .map(e -> new PriceObservation(id, e.getValue(), e.getKey(), new BigDecimal(prices.get(e.getValue())), NOW))
                        .toList());
            }
        };
    }

    static PriceSource source(String id, Map<String, String> prices) {
        return source(id, prices, true, false);
    }

    private static PriceOracleService service(SimpleMeterRegistry meters, PriceSource... sources) {
        return new PriceOracleService(List.of(sources), REGISTRY, PROPS, meters, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void readingBySymbolReachesConsensusAcrossSources() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        PriceOracleService oracle = service(meters,
                source("jupiter", Map.of("SOL", "180.10", "USDC", "1.0001")),
                source("pyth", Map.of("SOL", "179.90", "USDC", "0.9999")),
                source("coingecko", Map.of("SOL", "180.50")));
        StepVerifier.create(oracle.read(List.of("sol", "USDC")))
                .assertNext(r -> {
                    assertThat(r.accepted()).isTrue();
                    assertThat(r.of("SOL").orElseThrow().priceUsd()).isEqualByComparingTo("180.10");
                    assertThat(r.of("SOL").orElseThrow().sources()).containsExactly("coingecko", "jupiter", "pyth");
                    assertThat(r.of("SOL").orElseThrow().mint()).isEqualTo(TokenRegistry.NATIVE_SOL_MINT);
                    assertThat(r.of("USDC").orElseThrow().priceUsd()).isEqualByComparingTo("1.0000");
                    assertThat(r.of("USDC").orElseThrow().sources()).containsExactly("jupiter", "pyth"); // coingecko does not know USDC: fewer, still quorum
                    assertThat(r.limits()).isEqualTo(oracle.limits());
                    assertThat(r.readAt()).isEqualTo(NOW);
                })
                .verifyComplete();
        assertThat(meters.get(PriceOracleService.METRIC_CONSENSUS).tags("asset", "SOL", "result", "accepted").counter().count()).isEqualTo(1.0);
        assertThat(meters.get(PriceOracleService.METRIC_SOURCE_FETCH).tags("source", "pyth", "ok", "true").counter().count()).isEqualTo(1.0);
        assertThat(oracle.sourceIds()).containsExactly("jupiter", "pyth", "coingecko");
    }

    @Test
    void failedAndDisabledSourcesAreAbsentAndTheQuorumDecides() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        PriceOracleService oracle = service(meters,
                source("jupiter", Map.of("SOL", "180")),
                source("pyth", Map.of("SOL", "180"), true, true),      // errors
                source("coingecko", Map.of("SOL", "180"), false, false)); // disabled by configuration
        StepVerifier.create(oracle.read(List.of("SOL")))
                .assertNext(r -> {
                    assertThat(r.accepted()).isFalse();
                    assertThat(r.of("SOL").orElseThrow().refusals()).containsExactly(OracleRefusal.INSUFFICIENT_SOURCES);
                    assertThat(r.problems()).singleElement().asString().contains("1 usable source(s)");
                })
                .verifyComplete();
        assertThat(meters.get(PriceOracleService.METRIC_SOURCE_FETCH).tags("source", "pyth", "ok", "false").counter().count()).isEqualTo(1.0);
        assertThat(meters.get(PriceOracleService.METRIC_CONSENSUS).tags("asset", "SOL", "result", "insufficient_sources").counter().count()).isEqualTo(1.0);
        assertThat(meters.find(PriceOracleService.METRIC_SOURCE_FETCH).tags("source", "coingecko").counter()).isNull();
    }

    @Test
    void unknownSymbolIsReadAsNoObservations() {
        PriceOracleService oracle = service(new SimpleMeterRegistry(), source("jupiter", Map.of("SOL", "180")), source("pyth", Map.of("SOL", "180")));
        StepVerifier.create(oracle.read(List.of("SOL", "DOGE")))
                .assertNext(r -> {
                    assertThat(r.accepted()).isFalse();
                    assertThat(r.of("SOL").orElseThrow().accepted()).isTrue();
                    assertThat(r.of("DOGE").orElseThrow().refusals()).containsExactly(OracleRefusal.NO_OBSERVATIONS);
                })
                .verifyComplete();
        // nothing asked ⇒ an empty, accepted reading (nothing to price)
        StepVerifier.create(oracle.read(List.of())).assertNext(r -> assertThat(r.accepted()).isTrue()).verifyComplete();
    }

    /** A source whose answer can be changed between reads, and a clock that can be moved. */
    static final class MutableSource implements PriceSource {
        final String id;
        volatile Map<String, String> prices;
        volatile Instant at = NOW;

        MutableSource(String id, Map<String, String> prices) {
            this.id = id;
            this.prices = prices;
        }

        @Override public String id() { return id; }
        @Override public boolean enabled() { return true; }
        @Override public Mono<List<PriceObservation>> observe(Map<String, String> symbolByMint) {
            return Mono.just(symbolByMint.entrySet().stream()
                    .filter(e -> prices.containsKey(e.getValue()))
                    .map(e -> new PriceObservation(id, e.getValue(), e.getKey(), new BigDecimal(prices.get(e.getValue())), at))
                    .toList());
        }
    }

    static final class MutableClock extends Clock {
        volatile Instant now = NOW;
        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    @Test
    void circuitBreakerRemembersTheLastAcceptedConsensusPerAsset() {
        MutableSource jupiter = new MutableSource("jupiter", Map.of("SOL", "180"));
        MutableSource pyth = new MutableSource("pyth", Map.of("SOL", "180"));
        MutableClock clock = new MutableClock();
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        PriceOracleService oracle = new PriceOracleService(List.of(jupiter, pyth), REGISTRY, PROPS, meters, clock);
        StepVerifier.create(oracle.read(List.of("SOL"))).assertNext(r -> assertThat(r.accepted()).isTrue()).verifyComplete();

        // one minute later every source agrees on $150: a 16.7 % move inside the 5-minute interval — the sources
        // agree with each other, the breaker still refuses: agreement is not the same as plausibility
        clock.now = NOW.plusSeconds(60);
        jupiter.at = pyth.at = clock.now;
        jupiter.prices = pyth.prices = Map.of("SOL", "150");
        StepVerifier.create(oracle.read(List.of("SOL")))
                .assertNext(r -> {
                    assertThat(r.accepted()).isFalse();
                    assertThat(r.of("SOL").orElseThrow().refusals()).containsExactly(OracleRefusal.CIRCUIT_BREAKER);
                })
                .verifyComplete();
        assertThat(meters.get(PriceOracleService.METRIC_CONSENSUS).tags("asset", "SOL", "result", "circuit_breaker").counter().count()).isEqualTo(1.0);
        // the refused reading did not replace the reference: $150 keeps being refused while the interval lasts…
        clock.now = NOW.plusSeconds(120);
        jupiter.at = pyth.at = clock.now;
        StepVerifier.create(oracle.read(List.of("SOL"))).assertNext(r -> assertThat(r.accepted()).isFalse()).verifyComplete();
        // …and is accepted once the reference is older than the interval (the breaker is per interval, not forever)
        clock.now = NOW.plusSeconds(301);
        jupiter.at = pyth.at = clock.now;
        StepVerifier.create(oracle.read(List.of("SOL"))).assertNext(r -> assertThat(r.accepted()).isTrue()).verifyComplete();
        // a fresh process has no reference: accepted (post-MVP: persist the last consensus)
        PriceOracleService fresh = new PriceOracleService(List.of(jupiter, pyth), REGISTRY, PROPS, new SimpleMeterRegistry(), clock);
        StepVerifier.create(fresh.read(List.of("SOL"))).assertNext(r -> assertThat(r.accepted()).isTrue()).verifyComplete();
    }

    @Test
    void pricesForThePortfolioUseTheMedianAndFallBackLabelledWhenThereIsNoConsensus() {
        PriceOracleService oracle = service(new SimpleMeterRegistry(),
                source("jupiter", Map.of("SOL", "180.10", "USDC", "1")),
                source("pyth", Map.of("SOL", "179.90")));
        StepVerifier.create(oracle.prices(Set.of(TokenRegistry.NATIVE_SOL_MINT, TokenRegistry.USDC_MINT, "JUPyiwrYJFskUPiHa7hkeR8VUtAeFoSYbKedZNsDvCN")))
                .assertNext(q -> {
                    PriceProvider.PriceQuote sol = q.get(TokenRegistry.NATIVE_SOL_MINT);
                    assertThat(sol.priceUsd()).isEqualByComparingTo("180.00");
                    assertThat(sol.source()).isEqualTo("oracle:jupiter+pyth");
                    assertThat(sol.asOf()).isEqualTo(NOW);
                    // USDC has one source: no consensus ⇒ the static fallback keeps the screen alive, honestly labelled
                    PriceProvider.PriceQuote usdc = q.get(TokenRegistry.USDC_MINT);
                    assertThat(usdc.priceUsd()).isEqualByComparingTo("1");
                    assertThat(usdc.source()).isEqualTo(PriceOracleService.SOURCE_FALLBACK);
                    // JUP has neither a consensus nor a fallback: absent, as the PriceProvider contract says
                    assertThat(q).doesNotContainKey("JUPyiwrYJFskUPiHa7hkeR8VUtAeFoSYbKedZNsDvCN");
                })
                .verifyComplete();
        StepVerifier.create(oracle.prices(Set.of())).assertNext(q -> assertThat(q).isEmpty()).verifyComplete();
    }

    @Test
    void readingByMintValuesDevnetAliasesAsTheirMainnetMint() {
        PriceOracleService oracle = service(new SimpleMeterRegistry(), source("jupiter", Map.of("USDC", "1")), source("pyth", Map.of("USDC", "1")));
        StepVerifier.create(oracle.readMints(List.of("4zMMC9srt5Ri5X14GAgXhaHii3GnPAEERYPJgZJDncDU")))
                .assertNext(r -> {
                    OracleReading reading = r;
                    assertThat(reading.accepted()).isTrue();
                    assertThat(reading.of("USDC").orElseThrow().mint()).isEqualTo(TokenRegistry.USDC_MINT);
                })
                .verifyComplete();
    }
}
