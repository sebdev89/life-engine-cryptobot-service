package io.lifeengine.cryptobot.application.controlplane;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.lifeengine.cryptobot.adapters.marketdata.MarketDataProperties;
import io.lifeengine.cryptobot.adapters.marketdata.TokenRegistry;
import io.lifeengine.cryptobot.domain.portfolio.PortfolioSnapshot;
import io.lifeengine.cryptobot.domain.strategy.RebalanceIntent;
import io.lifeengine.cryptobot.domain.strategy.RebalanceLeg;
import io.lifeengine.cryptobot.domain.strategy.RebalancePlan;
import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RebalancePlannerTest {

    private final RebalancePlanner planner = new RebalancePlanner(new TokenRegistry(new MarketDataProperties(null, null, null, null, false)));

    @Test
    void solSeventyToFiftySellsExactlyTheExcess() {
        // total 1000: SOL 700 → target 500 ⇒ sell $200 = 2 SOL @ 100
        RebalancePlan plan = planner.plan(Fixtures.solHeavy(), new RebalanceIntent(Map.of("SOL", new BigDecimal("50")), "USDC"));
        assertThat(plan.legs()).hasSize(1);
        RebalanceLeg leg = plan.legs().get(0);
        assertThat(leg.action()).isEqualTo(RebalanceLeg.Action.SELL);
        assertThat(leg.symbol()).isEqualTo("SOL");
        assertThat(leg.amount()).isEqualByComparingTo("2");
        assertThat(leg.estimatedUsd()).isEqualByComparingTo("200");
        assertThat(leg.weightPctBefore()).isEqualByComparingTo("70");
        assertThat(leg.weightPctAfter()).isEqualByComparingTo("50");
        assertThat(plan.weightsAfter().get("USDC")).isEqualByComparingTo("50");
        assertThat(plan.turnoverUsd()).isEqualByComparingTo("200");
        assertThat(plan.summary()).contains("SELL 2 SOL");
    }

    @Test
    void projectionReflectsThePlan() {
        RebalancePlan plan = planner.plan(Fixtures.solHeavy(), new RebalanceIntent(Map.of("SOL", new BigDecimal("50")), "USDC"));
        PortfolioSnapshot after = planner.project(Fixtures.solHeavy(), plan);
        assertThat(after.totalUsd()).isEqualByComparingTo("1000");
        assertThat(after.position("SOL").orElseThrow().weightPct()).isEqualByComparingTo("50");
        assertThat(after.position("USDC").orElseThrow().amount()).isEqualByComparingTo("500");
    }

    @Test
    void alreadyWithinTargetIsANoop() {
        RebalancePlan plan = planner.plan(Fixtures.solHeavy(), new RebalanceIntent(Map.of("SOL", new BigDecimal("70")), "USDC"));
        assertThat(plan.isNoop()).isTrue();
    }

    @Test
    void buyingMoreThanTheCounterAssetCanFundIsRejected() {
        // SOL 70% → 99% would need $290 more USDC than the $300 held... still fundable; ask for JUP 50% (not held, no price)
        assertThatThrownBy(() -> planner.plan(Fixtures.solHeavy(), new RebalanceIntent(Map.of("JUP", new BigDecimal("50")), "USDC")))
                .isInstanceOf(ControlPlaneExceptions.InvalidRequest.class)
                .hasMessageContaining("JUP");
        // SOL 30% → 70% on the balanced portfolio needs $400 but only $350 USDC exist
        assertThatThrownBy(() -> planner.plan(Fixtures.balanced(), new RebalanceIntent(Map.of("SOL", new BigDecimal("70")), "USDC")))
                .isInstanceOf(ControlPlaneExceptions.InvalidRequest.class)
                .hasMessageContaining("Not enough USDC");
    }

    @Test
    void rejectsSillyTargets() {
        assertThatThrownBy(() -> planner.plan(Fixtures.solHeavy(), new RebalanceIntent(Map.of("SOL", new BigDecimal("150")), "USDC")))
                .isInstanceOf(ControlPlaneExceptions.InvalidRequest.class);
        assertThatThrownBy(() -> planner.plan(Fixtures.solHeavy(), new RebalanceIntent(Map.of("USDC", new BigDecimal("10")), "USDC")))
                .isInstanceOf(ControlPlaneExceptions.InvalidRequest.class);
        assertThatThrownBy(() -> planner.plan(Fixtures.snapshot(new Object[][] {}), new RebalanceIntent(Map.of("SOL", new BigDecimal("10")), "USDC")))
                .isInstanceOf(ControlPlaneExceptions.InvalidRequest.class);
    }
}
