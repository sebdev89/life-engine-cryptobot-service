package io.lifeengine.cryptobot.application.chaos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.lifeengine.cryptobot.core.oracle.OracleConsensus;
import io.lifeengine.cryptobot.core.oracle.OracleLimits;
import io.lifeengine.cryptobot.core.oracle.OracleRefusal;
import io.lifeengine.cryptobot.core.oracle.PriceObservation;
import io.lifeengine.cryptobot.core.oracle.PriceOracle;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * the demo's adversarial price is injected into what the sources said, and the real
 * {@link PriceOracle} refuses for the real reason. Nothing here bypasses the oracle: the
 * injection is one more observation, the limits are the limits.
 */
class PriceChaosTest {

    static final Instant NOW = Instant.parse("2026-09-20T12:00:00Z");
    static final OracleLimits LIMITS = new OracleLimits(2, 120, 100, 1_000, 300);
    static final List<String> SOURCES = List.of("jupiter-price-v3", "pyth-hermes", "coingecko-simple");
    static final String MINT = "So11111111111111111111111111111111111111112";

    static List<PriceObservation> real() {
        return List.of(obs("jupiter-price-v3", "180.10"), obs("pyth-hermes", "180.05"), obs("coingecko-simple", "179.90"));
    }

    static PriceObservation obs(String source, String price) {
        return new PriceObservation(source, "SOL", MINT, new BigDecimal(price), NOW);
    }

    @Test
    @DisplayName("one source × 0.1: that source's quote is replaced, the others kept ⇒ DEVIATION_EXCEEDED")
    void oneDeviatingSource() {
        PriceChaos chaos = new PriceChaos();
        chaos.arm(new PriceChaos.Override("sol", "pyth-hermes", null, new BigDecimal("0.1"), null));
        List<PriceObservation> seen = chaos.apply("SOL", MINT, real(), SOURCES, NOW);
        assertThat(seen).hasSize(3);
        assertThat(seen).filteredOn(o -> o.source().equals("pyth-hermes")).extracting(PriceObservation::priceUsd).singleElement()
                .satisfies(p -> assertThat(p).isEqualByComparingTo("18.005"));
        assertThat(seen).filteredOn(o -> !o.source().equals("pyth-hermes")).extracting(PriceObservation::priceUsd)
                .allSatisfy(p -> assertThat(p).isGreaterThan(new BigDecimal("179")));
        OracleConsensus c = PriceOracle.consensus("SOL", MINT, seen, null, NOW, LIMITS);
        assertThat(c.accepted()).isFalse();
        assertThat(c.refusals()).containsExactly(OracleRefusal.DEVIATION_EXCEEDED);
        assertThat(chaos.injections()).singleElement().satisfies(i -> {
            assertThat(i.source()).isEqualTo("pyth-hermes");
            assertThat(i.realPriceUsd()).isEqualTo("180.05");
            assertThat(i.injectedPriceUsd()).isEqualTo("18.005");
        });
    }

    @Test
    @DisplayName("a source that did not answer is invented from the median of the others, so the demo does not depend on which feeds are up")
    void silentSourceIsInvented() {
        PriceChaos chaos = new PriceChaos();
        chaos.arm(new PriceChaos.Override("SOL", "pyth-hermes", null, new BigDecimal("0.1"), null));
        List<PriceObservation> seen = chaos.apply("SOL", MINT, List.of(obs("jupiter-price-v3", "180"), obs("coingecko-simple", "182")), SOURCES, NOW);
        assertThat(seen).extracting(PriceObservation::source).containsExactlyInAnyOrder("jupiter-price-v3", "coingecko-simple", "pyth-hermes");
        assertThat(seen).filteredOn(o -> o.source().equals("pyth-hermes")).extracting(PriceObservation::priceUsd).singleElement()
                .satisfies(p -> assertThat(p).isEqualByComparingTo("18.1")); // median(180, 182) × 0.1
        assertThat(PriceOracle.consensus("SOL", MINT, seen, null, NOW, LIMITS).refusals()).containsExactly(OracleRefusal.DEVIATION_EXCEEDED);
    }

    @Test
    @DisplayName("* with ageSeconds: every source keeps its price but is back-dated ⇒ INSUFFICIENT_SOURCES with STALE rejections")
    void everySourceStale() {
        PriceChaos chaos = new PriceChaos();
        chaos.arm(new PriceChaos.Override("SOL", "*", null, null, 900L));
        List<PriceObservation> seen = chaos.apply("SOL", MINT, real(), SOURCES, NOW);
        assertThat(seen).hasSize(3);
        assertThat(seen).allSatisfy(o -> assertThat(o.observedAt()).isEqualTo(NOW.minusSeconds(900)));
        assertThat(seen).extracting(PriceObservation::priceUsd).map(BigDecimal::toPlainString).containsExactlyInAnyOrder("180.10", "180.05", "179.90");
        OracleConsensus c = PriceOracle.consensus("SOL", MINT, seen, null, NOW, LIMITS);
        assertThat(c.refusals()).containsExactly(OracleRefusal.INSUFFICIENT_SOURCES);
        assertThat(c.rejected()).hasSize(3).allSatisfy(r -> assertThat(r.reason()).isEqualTo("STALE"));
    }

    @Test
    @DisplayName("* with factor: the whole market moves ⇒ accepted, and the breaker trips against the last accepted consensus")
    void wholeMarketCrashTripsTheBreaker() {
        PriceChaos chaos = new PriceChaos();
        List<PriceObservation> earlier = real().stream().map(o -> new PriceObservation(o.source(), o.asset(), o.mint(), o.priceUsd(), NOW.minusSeconds(30))).toList();
        OracleConsensus before = PriceOracle.consensus("SOL", MINT, earlier, null, NOW.minusSeconds(30), LIMITS);
        assertThat(before.accepted()).isTrue();
        chaos.arm(new PriceChaos.Override("SOL", "*", null, new BigDecimal("0.1"), null));
        List<PriceObservation> seen = chaos.apply("SOL", MINT, real(), SOURCES, NOW);
        OracleConsensus c = PriceOracle.consensus("SOL", MINT, seen, before, NOW, LIMITS);
        assertThat(c.refusals()).containsExactly(OracleRefusal.CIRCUIT_BREAKER);
        assertThat(c.priceUsd()).isEqualByComparingTo("18.005");
    }

    @Test
    @DisplayName("an asset with nothing armed is untouched; disarm restores; the env spec parses")
    void armDisarmAndParse() {
        PriceChaos chaos = new PriceChaos();
        assertThat(chaos.armed()).isFalse();
        List<PriceObservation> untouched = real();
        assertThat(chaos.apply("SOL", MINT, untouched, SOURCES, NOW)).isSameAs(untouched);
        List<PriceChaos.Override> parsed = PriceChaos.Override.parse("SOL:pyth-hermes:factor=0.1; USDC:*:price=0.5:age=60");
        assertThat(parsed).hasSize(2);
        assertThat(parsed.get(0).asset()).isEqualTo("SOL");
        assertThat(parsed.get(0).source()).isEqualTo("pyth-hermes");
        assertThat(parsed.get(0).factor()).isEqualByComparingTo("0.1");
        assertThat(parsed.get(1).everySource()).isTrue();
        assertThat(parsed.get(1).priceUsd()).isEqualByComparingTo("0.5");
        assertThat(parsed.get(1).ageSeconds()).isEqualTo(60L);
        parsed.forEach(chaos::arm);
        assertThat(chaos.overrides()).extracting(PriceChaos.Override::asset).containsExactly("SOL", "USDC");
        List<PriceObservation> real = real();
        assertThat(chaos.apply("JUP", MINT, real, SOURCES, NOW)).isSameAs(real);
        assertThat(chaos.disarm("sol")).isPresent();
        assertThat(chaos.apply("SOL", MINT, real, SOURCES, NOW)).isSameAs(real);
        chaos.disarmAll();
        assertThat(chaos.armed()).isFalse();
        assertThatThrownBy(() -> new PriceChaos.Override("SOL", "*", null, null, null)).hasMessageContaining("nothing to inject");
        assertThatThrownBy(() -> PriceChaos.Override.parse("SOL:*:volume=1")).hasMessageContaining("unknown key");
        assertThatThrownBy(() -> new PriceChaos.Override("SOL", "*", new BigDecimal("-1"), null, null)).hasMessageContaining("priceUsd");
    }
}
