package io.lifeengine.cryptobot.core.oracle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The oracle as a pure function: same quotes, same limits ⇒ same median, same refusals, same hash. */
class PriceOracleTest {

    private static final Instant NOW = Instant.parse("2026-09-18T12:00:00Z");
    private static final String SOL_MINT = "So11111111111111111111111111111111111111112";
    private static final OracleLimits LIMITS = new OracleLimits(2, 60, 100, 1_000, 300);

    private static PriceObservation obs(String source, String price, long ageSeconds) {
        return new PriceObservation(source, "SOL", SOL_MINT, price == null ? null : new BigDecimal(price), NOW.minusSeconds(ageSeconds));
    }

    private static OracleConsensus consensus(List<PriceObservation> obs) {
        return PriceOracle.consensus("SOL", SOL_MINT, obs, null, NOW, LIMITS);
    }

    @Test
    void medianOfThreeAgreeingSourcesIsAccepted() {
        OracleConsensus c = consensus(List.of(obs("jupiter", "180.10", 1), obs("pyth", "179.95", 0), obs("coingecko", "180.40", 30)));
        assertThat(c.accepted()).isTrue();
        assertThat(c.priceUsd()).isEqualByComparingTo("180.10");
        assertThat(c.sources()).containsExactly("coingecko", "jupiter", "pyth"); // deterministic: by source id
        assertThat(c.asOf()).isEqualTo(NOW.minusSeconds(30)); // the oldest fact the median depends on
        assertThat(c.refusals()).isEmpty();
        assertThat(c.quotesHash()).matches("sha256:[0-9a-f]{64}");
    }

    @Test
    void evenCountAveragesTheTwoMiddleValues() {
        OracleConsensus c = consensus(List.of(obs("a", "100", 0), obs("b", "101", 0)));
        assertThat(c.accepted()).isTrue();
        assertThat(c.priceUsd()).isEqualByComparingTo("100.5");
    }

    @Test
    void oneSourceIsAnOpinionNotAConsensus() {
        OracleConsensus c = consensus(List.of(obs("jupiter", "180", 0)));
        assertThat(c.accepted()).isFalse();
        assertThat(c.priceUsd()).isNull();
        assertThat(c.refusals()).containsExactly(OracleRefusal.INSUFFICIENT_SOURCES);
        assertThat(c.problems().get(0)).contains("1 usable source(s)").contains("quorum is 2");
    }

    @Test
    void noObservationsAtAllIsItsOwnRefusal() {
        OracleConsensus c = consensus(List.of());
        assertThat(c.accepted()).isFalse();
        assertThat(c.refusals()).containsExactly(OracleRefusal.NO_OBSERVATIONS);
        // the hash still names what was (not) seen, so the receipt can say "nothing"
        assertThat(c.quotesHash()).isEqualTo(OracleConsensus.quotesHash("SOL", SOL_MINT, List.of()));
    }

    @Test
    void staleFutureInvalidAndDuplicateObservationsAreRejectedWithAReason() {
        OracleConsensus c = consensus(List.of(
                obs("jupiter", "180", 0),
                obs("pyth", "180", 61),                       // older than max_age
                obs("coingecko", "180", -10),                 // 10 s in the future, beyond the 5 s skew
                obs("binance", "0", 0),                       // non-positive
                new PriceObservation("kraken", "SOL", SOL_MINT, new BigDecimal("180"), null), // no timestamp
                obs("jupiter", "170", 5)));                   // second quote of a source already used
        assertThat(c.accepted()).isFalse();
        assertThat(c.refusals()).containsExactly(OracleRefusal.INSUFFICIENT_SOURCES);
        assertThat(c.used()).extracting(PriceObservation::source).containsExactly("jupiter");
        assertThat(c.rejected()).extracting(OracleConsensus.Rejected::reason)
                .containsExactlyInAnyOrder("STALE", "FUTURE", "INVALID_PRICE", "NO_TIMESTAMP", "DUPLICATE_SOURCE");
        // the newest quote of a duplicated source is the one used
        assertThat(c.used().get(0).priceUsd()).isEqualByComparingTo("180");
    }

    @Test
    void observationsOfAnotherAssetNeverCount() {
        PriceObservation usdc = new PriceObservation("pyth", "USDC", "EPjF", BigDecimal.ONE, NOW);
        OracleConsensus c = consensus(List.of(obs("jupiter", "180", 0), usdc));
        assertThat(c.accepted()).isFalse();
        assertThat(c.rejected()).extracting(OracleConsensus.Rejected::reason).containsExactly("OTHER_ASSET");
    }

    @Test
    void sourcesThatDisagreeBeyondTheBoundAreRefusedTogether() {
        // paper §22: the oracle that says $18 while the world says $180 must not win — nor be averaged in
        OracleConsensus c = consensus(List.of(obs("jupiter", "180", 0), obs("pyth", "180.5", 0), obs("corrupt", "18", 0)));
        assertThat(c.accepted()).isFalse();
        assertThat(c.priceUsd()).isEqualByComparingTo("180"); // the median is kept for the audit trail…
        assertThat(c.refusals()).containsExactly(OracleRefusal.DEVIATION_EXCEEDED); // …but nothing may rely on it
        assertThat(c.problems()).singleElement().asString().contains("corrupt says 18").contains("9000 bps apart");
    }

    @Test
    void twoSourcesThatDisagreeCannotOutvoteEachOther() {
        OracleConsensus c = consensus(List.of(obs("jupiter", "180", 0), obs("pyth", "18", 0)));
        assertThat(c.accepted()).isFalse();
        assertThat(c.refusals()).containsExactly(OracleRefusal.DEVIATION_EXCEEDED);
        assertThat(c.problems()).hasSize(2); // both are far from the 99 median
    }

    @Test
    void circuitBreakerTripsOnAJumpAgainstTheLastAcceptedConsensus() {
        OracleConsensus earlier = PriceOracle.consensus("SOL", SOL_MINT, List.of(obs("jupiter", "180", 120), obs("pyth", "180", 120)), null,
                NOW.minusSeconds(120), LIMITS);
        assertThat(earlier.accepted()).isTrue();
        // two minutes later every source agrees on $150: −16.7 % in 120 s, over the 10 %-per-5-min breaker
        OracleConsensus jump = PriceOracle.consensus("SOL", SOL_MINT, List.of(obs("jupiter", "150", 0), obs("pyth", "150", 0)), earlier, NOW, LIMITS);
        assertThat(jump.accepted()).isFalse();
        assertThat(jump.refusals()).containsExactly(OracleRefusal.CIRCUIT_BREAKER);
        assertThat(jump.problems()).singleElement().asString().contains("moved 1667 bps").contains("180 → 150");
        // a 5 % move within the interval is fine
        OracleConsensus drift = PriceOracle.consensus("SOL", SOL_MINT, List.of(obs("jupiter", "171", 0), obs("pyth", "171", 0)), earlier, NOW, LIMITS);
        assertThat(drift.accepted()).isTrue();
        // a reference older than the interval is not compared: the breaker is per interval, not forever
        OracleConsensus old = PriceOracle.consensus("SOL", SOL_MINT, List.of(obs("jupiter", "180", 400), obs("pyth", "180", 400)), null,
                NOW.minusSeconds(400), LIMITS);
        OracleConsensus later = PriceOracle.consensus("SOL", SOL_MINT, List.of(obs("jupiter", "150", 0), obs("pyth", "150", 0)), old, NOW, LIMITS);
        assertThat(later.accepted()).isTrue();
        // a refused previous is never a reference
        OracleConsensus refused = PriceOracle.consensus("SOL", SOL_MINT, List.of(obs("jupiter", "180", 0)), null, NOW, LIMITS);
        assertThat(PriceOracle.consensus("SOL", SOL_MINT, List.of(obs("jupiter", "150", 0), obs("pyth", "150", 0)), refused, NOW, LIMITS).accepted()).isTrue();
    }

    @Test
    void sameQuotesSameLimitsSameHashRegardlessOfOrder() {
        List<PriceObservation> a = List.of(obs("jupiter", "180.10", 1), obs("pyth", "179.95", 0), obs("coingecko", "180.40", 30));
        List<PriceObservation> b = List.of(obs("coingecko", "180.40", 30), obs("pyth", "179.95", 0), obs("jupiter", "180.10", 1));
        OracleConsensus ca = consensus(a);
        OracleConsensus cb = consensus(b);
        assertThat(ca.quotesHash()).isEqualTo(cb.quotesHash());
        assertThat(ca.priceUsd()).isEqualByComparingTo(cb.priceUsd());
        assertThat(ca.used()).isEqualTo(cb.used());
        // one different digit ⇒ another hash
        List<PriceObservation> c = List.of(obs("jupiter", "180.11", 1), obs("pyth", "179.95", 0), obs("coingecko", "180.40", 30));
        assertThat(consensus(c).quotesHash()).isNotEqualTo(ca.quotesHash());
        // and a different limit set hashes differently even with the same quotes
        assertThat(new OracleLimits(3, 60, 100, 1_000, 300).hash()).isNotEqualTo(LIMITS.hash());
    }

    @Test
    void deviationIsRoundedUpNeverIntoTheLimit() {
        assertThat(PriceOracle.deviationBps(new BigDecimal("101"), new BigDecimal("100"))).isEqualTo(100);
        assertThat(PriceOracle.deviationBps(new BigDecimal("101.001"), new BigDecimal("100"))).isEqualTo(101);
        assertThat(PriceOracle.deviationBps(new BigDecimal("99"), new BigDecimal("100"))).isEqualTo(100);
        assertThat(PriceOracle.deviationBps(new BigDecimal("18"), new BigDecimal("180"))).isEqualTo(9000);
        assertThat(PriceOracle.deviationBps(new BigDecimal("180"), BigDecimal.ZERO)).isEqualTo(OracleLimits.MAX_BPS + 1);
        assertThat(PriceOracle.deviationBps(null, new BigDecimal("100"))).isEqualTo(OracleLimits.MAX_BPS + 1);
    }

    @Test
    void limitsThatAreNotAnEnvelopeCannotBeLoaded() {
        assertThatThrownBy(() -> new OracleLimits(1, 60, 100, 1_000, 300)).hasMessageContaining("min_sources");
        assertThatThrownBy(() -> new OracleLimits(2, 0, 100, 1_000, 300)).hasMessageContaining("max_age_seconds");
        assertThatThrownBy(() -> new OracleLimits(2, 60, 10_001, 1_000, 300)).hasMessageContaining("max_deviation_bps");
        assertThatThrownBy(() -> new OracleLimits(2, 60, 100, -1, 300)).hasMessageContaining("max_move_bps");
        assertThatThrownBy(() -> PriceOracle.consensus("SOL", SOL_MINT, List.of(), null, NOW, null)).hasMessageContaining("limits");
    }

    @Test
    void readingAggregatesAssetsAndIsUnknownAsSoonAsOneIsRefused() {
        OracleConsensus sol = consensus(List.of(obs("jupiter", "180", 10), obs("pyth", "180", 2)));
        OracleConsensus usdc = PriceOracle.consensus("USDC", "EPjF", List.of(
                new PriceObservation("jupiter", "USDC", "EPjF", new BigDecimal("1.0001"), NOW),
                new PriceObservation("pyth", "USDC", "EPjF", new BigDecimal("0.9999"), NOW.minusSeconds(40))), null, NOW, LIMITS);
        OracleReading ok = new OracleReading(NOW, LIMITS, List.of(usdc, sol));
        assertThat(ok.accepted()).isTrue();
        assertThat(ok.assets()).extracting(OracleConsensus::asset).containsExactly("SOL", "USDC"); // sorted
        assertThat(ok.asOf()).contains(NOW.minusSeconds(40));
        assertThat(ok.of("sol")).isPresent();
        assertThat(ok.problems()).isEmpty();
        assertThat(ok.quotesHash()).matches("sha256:[0-9a-f]{64}");
        assertThat(ok.limitsHash()).isEqualTo(LIMITS.hash());

        OracleConsensus lonely = consensus(List.of(obs("jupiter", "180", 0)));
        OracleReading bad = new OracleReading(NOW, LIMITS, List.of(usdc, lonely));
        assertThat(bad.accepted()).isFalse();
        assertThat(bad.asOf()).isEmpty();
        assertThat(bad.problems()).singleElement().asString().startsWith("SOL: 1 usable source(s)");
        assertThat(bad.quotesHash()).isNotEqualTo(ok.quotesHash());
    }
}
