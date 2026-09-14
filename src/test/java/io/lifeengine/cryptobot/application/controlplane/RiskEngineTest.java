package io.lifeengine.cryptobot.application.controlplane;

import static org.assertj.core.api.Assertions.assertThat;

import io.lifeengine.cryptobot.adapters.marketdata.TokenRegistry;
import io.lifeengine.cryptobot.domain.portfolio.PortfolioDiff;
import io.lifeengine.cryptobot.domain.portfolio.PortfolioSnapshot;
import io.lifeengine.cryptobot.domain.risk.RiskFinding;
import io.lifeengine.cryptobot.domain.risk.RiskReport;
import io.lifeengine.cryptobot.domain.risk.RiskSeverity;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class RiskEngineTest {

    private final RiskEngine engine = new RiskEngine(new RiskRulesProperties(null, null, null, null, null));

    @Test
    void solAtSeventyPercentIsHighConcentration() {
        RiskReport report = engine.evaluate(Fixtures.solHeavy(), null);
        assertThat(report.overall()).isEqualTo(RiskSeverity.HIGH);
        RiskFinding conc = report.findings().stream().filter(f -> f.code().equals(RiskEngine.CONCENTRATION)).findFirst().orElseThrow();
        assertThat(conc.severity()).isEqualTo(RiskSeverity.HIGH);
        assertThat(conc.asset()).isEqualTo("SOL");
        assertThat(conc.metric()).isEqualByComparingTo("70");
        assertThat(conc.threshold()).isEqualByComparingTo("60");
        // 30% USDC → no NO_STABLES finding
        assertThat(report.findings()).noneMatch(f -> f.code().equals(RiskEngine.NO_STABLES));
    }

    @Test
    void balancedPortfolioHasNoFindings() {
        RiskReport report = engine.evaluate(Fixtures.balanced(), null);
        assertThat(report.findings()).isEmpty();
        assertThat(report.overall()).isEqualTo(RiskSeverity.LOW);
        assertThat(report.score()).isZero();
    }

    @Test
    void stablecoinsNeverTriggerConcentration() {
        PortfolioSnapshot allStable = Fixtures.snapshot(new Object[][] {{TokenRegistry.USDC_MINT, "USDC", "1000", "1", true, false}});
        RiskReport report = engine.evaluate(allStable, null);
        assertThat(report.findings()).noneMatch(f -> f.code().equals(RiskEngine.CONCENTRATION));
    }

    @Test
    void unpricedTokensAndDustAreReported() {
        PortfolioSnapshot s = Fixtures.snapshot(new Object[][] {
            {TokenRegistry.NATIVE_SOL_MINT, "SOL", "1", "100", false, true},
            {TokenRegistry.USDC_MINT, "USDC", "100", "1", true, false},
            {"Unknown111111111111111111111111111111111111", "UNKNOWN-Unkn", "5", null, false, false},
            {"JUPyiwrYJFskUPiHa7hkeR8VUtAeFoSYbKedZNsDvCN", "JUP", "0.1", "0.5", false, false},
        });
        RiskReport report = engine.evaluate(s, null);
        assertThat(report.findings()).extracting(RiskFinding::code)
                .contains(RiskEngine.UNKNOWN_TOKENS, RiskEngine.DUST_POSITIONS);
    }

    @Test
    void sharpDropSincePreviousSnapshotIsHigh() {
        PortfolioDiff diff = new PortfolioDiff(Instant.now(), Instant.now(), new BigDecimal("1000"), new BigDecimal("900"),
                new BigDecimal("-10"), List.of(), "SOL", new BigDecimal("-6"));
        RiskReport report = engine.evaluate(Fixtures.balanced(), diff);
        RiskFinding move = report.findings().stream().filter(f -> f.code().equals(RiskEngine.SHARP_MOVE)).findFirst().orElseThrow();
        assertThat(move.severity()).isEqualTo(RiskSeverity.HIGH);
        assertThat(move.asset()).isEqualTo("SOL");
    }

    @Test
    void emptyPortfolioIsCalmNotBroken() {
        PortfolioSnapshot empty = Fixtures.snapshot(new Object[][] {});
        RiskReport report = engine.evaluate(empty, null);
        assertThat(report.findings()).extracting(RiskFinding::code).containsExactly(RiskEngine.EMPTY_PORTFOLIO);
    }
}
