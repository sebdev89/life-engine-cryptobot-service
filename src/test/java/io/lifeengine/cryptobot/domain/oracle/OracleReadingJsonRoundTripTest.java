package io.lifeengine.cryptobot.domain.oracle;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.lifeengine.cryptobot.domain.policy.PolicyDecision;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

/**
 * The reading travels inside {@link PolicyDecision}, which is persisted as the proposal's JSONB
 * document and read back at execution: what comes back must hash and judge exactly like what
 * went in, or the state reference in the receipt would not name the persisted quotes.
 */
class OracleReadingJsonRoundTripTest {

    private static final Instant NOW = Instant.parse("2026-09-18T12:00:00Z");
    private static final OracleLimits LIMITS = new OracleLimits(2, 60, 100, 1_000, 300);
    private final ObjectMapper mapper = Jackson2ObjectMapperBuilder.json().build();

    @Test
    void policyDecisionWithAReadingSurvivesTheDocument() throws Exception {
        String sol = "So11111111111111111111111111111111111111112";
        OracleConsensus accepted = PriceOracle.consensus("SOL", sol, List.of(
                new PriceObservation("jupiter-price-v3", "SOL", sol, new BigDecimal("180.10"), NOW.minusSeconds(1)),
                new PriceObservation("pyth-hermes", "SOL", sol, new BigDecimal("179.95"), NOW),
                new PriceObservation("coingecko-simple", "SOL", sol, new BigDecimal("18"), NOW.minusSeconds(200))), // stale ⇒ rejected
                null, NOW, LIMITS);
        OracleConsensus refused = PriceOracle.consensus("USDC", "EPjF", List.of(
                new PriceObservation("jupiter-price-v3", "USDC", "EPjF", new BigDecimal("1.0001"), NOW)), null, NOW, LIMITS);
        OracleReading reading = new OracleReading(NOW, LIMITS, List.of(accepted, refused));
        PolicyDecision decision = new PolicyDecision(false, false, List.of(new PolicyDecision.Violation("ORACLE_INTEGRITY", "USDC: no consensus")),
                List.of(), List.of("ORACLE_INTEGRITY"), NOW, null, reading);

        String json = mapper.writeValueAsString(decision);
        PolicyDecision back = mapper.readValue(json, PolicyDecision.class);

        assertThat(back.oracle()).isNotNull();
        assertThat(back.oracle().quotesHash()).isEqualTo(reading.quotesHash());
        assertThat(back.oracle().limitsHash()).isEqualTo(reading.limitsHash());
        assertThat(back.oracle().accepted()).isFalse();
        assertThat(back.oracle().of("SOL")).isPresent();
        assertThat(back.oracle().of("SOL").get().accepted()).isTrue();
        assertThat(back.oracle().of("SOL").get().priceUsd()).isEqualByComparingTo("180.025");
        assertThat(back.oracle().of("SOL").get().rejected()).singleElement().extracting(OracleConsensus.Rejected::reason).isEqualTo("STALE");
        assertThat(back.oracle().of("USDC").get().refusals()).containsExactly(OracleRefusal.INSUFFICIENT_SOURCES);
        assertThat(back.oracle().problems()).isEqualTo(reading.problems());
        // Nothing but sources, mints, prices and timestamps is written: the document names no key and no secret.
        assertThat(json).contains("\"quotesHash\":\"sha256:").contains("\"source\":\"pyth-hermes\"").doesNotContain("secret");
    }
}
