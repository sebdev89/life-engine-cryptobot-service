package io.lifeengine.cryptobot.application.controlplane;

import static org.assertj.core.api.Assertions.assertThat;

import io.lifeengine.cryptobot.adapters.marketdata.MarketDataProperties;
import io.lifeengine.cryptobot.adapters.marketdata.TokenRegistry;
import io.lifeengine.cryptobot.adapters.solana.SolanaCluster;
import io.lifeengine.cryptobot.domain.policy.PolicyDecision;
import io.lifeengine.cryptobot.domain.strategy.RebalanceIntent;
import io.lifeengine.cryptobot.domain.strategy.RebalancePlan;
import io.lifeengine.cryptobot.domain.transactions.ActionProposal;
import io.lifeengine.cryptobot.domain.transactions.PreparedTransaction;
import io.lifeengine.cryptobot.domain.transactions.ProposalStatus;
import io.lifeengine.cryptobot.domain.transactions.SimulationOutcome;
import io.lifeengine.cryptobot.domain.wallet.Wallet;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PolicyEngineTest {

    private static final Instant NOW = Instant.parse("2026-09-14T12:00:00Z");
    private final RebalancePlanner planner = new RebalancePlanner(new TokenRegistry(new MarketDataProperties(null, null, null, null, false)));

    private static PolicyEngine engine(PolicyProperties props) {
        return new PolicyEngine(props, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static PolicyProperties defaults() {
        return new PolicyProperties(true, "devnet", new BigDecimal("500"), new BigDecimal("50"), List.of("SOL", "USDC", "USDT"),
                Duration.ofSeconds(60), Fixtures.VAULT, 2_000_000_000L, Duration.ofMinutes(30));
    }

    private ActionProposal proposal(Wallet wallet, BigDecimal targetSolPct, boolean withTx, boolean simOk) {
        RebalancePlan plan = planner.plan(Fixtures.solHeavy(), new RebalanceIntent(Map.of("SOL", targetSolPct), "USDC"));
        long lamports = plan.legs().get(0).amount().multiply(new BigDecimal("1000000000")).longValue();
        PreparedTransaction tx = withTx ? new PreparedTransaction(wallet.cluster().id(), wallet.address(), Fixtures.VAULT, lamports, "bh", 1L, "AA==", "AA==", "transfer") : null;
        SimulationOutcome sim = new SimulationOutcome(null, new SimulationOutcome.Onchain(simOk, simOk ? null : "boom", 150L, List.of(), wallet.cluster().id()));
        return new ActionProposal(UUID.randomUUID(), wallet.id(), wallet.ownerUserId(), wallet.address(), wallet.cluster().id(), ProposalStatus.SIMULATED,
                "REBALANCE", "t", null, "tester", new RebalanceIntent(Map.of("SOL", targetSolPct), "USDC"), plan, null, null, null, sim, tx, null, null,
                null, null, NOW.plusSeconds(1800), NOW, NOW);
    }

    @Test
    void happyPathOnDevnetWithSignerIsAllowedAndExecutable() {
        Wallet w = Fixtures.wallet(SolanaCluster.DEVNET);
        PolicyDecision d = engine(defaults()).evaluate(proposal(w, new BigDecimal("50"), true, true), w, Optional.empty(), Optional.of(w.address()));
        assertThat(d.allowed()).isTrue();
        assertThat(d.executable()).isTrue();
        assertThat(d.violations()).isEmpty();
        assertThat(d.executionViolations()).isEmpty();
        assertThat(d.rulesApplied()).contains(PolicyEngine.RULE_MAX_TRADE_USD, PolicyEngine.RULE_SIGNER, PolicyEngine.RULE_CLUSTER);
    }

    @Test
    void mainnetWalletIsPaperTradeOnly() {
        Wallet w = Fixtures.wallet(SolanaCluster.MAINNET_BETA);
        PolicyDecision d = engine(defaults()).evaluate(proposal(w, new BigDecimal("50"), true, true), w, Optional.empty(), Optional.of("someoneElse"));
        assertThat(d.allowed()).isTrue();
        assertThat(d.executable()).isFalse();
        assertThat(d.executionViolations()).extracting(PolicyDecision.Violation::rule)
                .contains(PolicyEngine.RULE_CLUSTER, PolicyEngine.RULE_SIGNER);
    }

    @Test
    void tradeOverTheUsdCapIsBlocked() {
        Wallet w = Fixtures.wallet(SolanaCluster.DEVNET);
        PolicyProperties tight = new PolicyProperties(true, "devnet", new BigDecimal("100"), new BigDecimal("50"), List.of("SOL", "USDC"),
                Duration.ofSeconds(60), Fixtures.VAULT, 2_000_000_000L, Duration.ofMinutes(30));
        PolicyDecision d = engine(tight).evaluate(proposal(w, new BigDecimal("50"), true, true), w, Optional.empty(), Optional.of(w.address()));
        assertThat(d.allowed()).isFalse();
        assertThat(d.violations()).extracting(PolicyDecision.Violation::rule).containsExactly(PolicyEngine.RULE_MAX_TRADE_USD);
    }

    @Test
    void tradeOverThePortfolioPercentageIsBlocked() {
        Wallet w = Fixtures.wallet(SolanaCluster.DEVNET);
        // SOL 70 → 10 moves 60% of the portfolio, over the 50% cap (and $600 > $500)
        PolicyDecision d = engine(defaults()).evaluate(proposal(w, new BigDecimal("10"), true, true), w, Optional.empty(), Optional.of(w.address()));
        assertThat(d.allowed()).isFalse();
        assertThat(d.violations()).extracting(PolicyDecision.Violation::rule).contains(PolicyEngine.RULE_MAX_TRADE_PCT, PolicyEngine.RULE_MAX_TRADE_USD);
    }

    @Test
    void assetOutsideAllowlistIsBlocked() {
        Wallet w = Fixtures.wallet(SolanaCluster.DEVNET);
        PolicyProperties noSol = new PolicyProperties(true, "devnet", new BigDecimal("500"), new BigDecimal("50"), List.of("USDC"),
                Duration.ofSeconds(60), Fixtures.VAULT, 2_000_000_000L, Duration.ofMinutes(30));
        PolicyDecision d = engine(noSol).evaluate(proposal(w, new BigDecimal("50"), true, true), w, Optional.empty(), Optional.of(w.address()));
        assertThat(d.allowed()).isFalse();
        assertThat(d.violations()).extracting(PolicyDecision.Violation::rule).contains(PolicyEngine.RULE_ASSET_ALLOWLIST);
    }

    @Test
    void cooldownBlocksBackToBackExecutions() {
        Wallet w = Fixtures.wallet(SolanaCluster.DEVNET);
        PolicyDecision d = engine(defaults()).evaluate(proposal(w, new BigDecimal("50"), true, true), w, Optional.of(NOW.minusSeconds(10)), Optional.of(w.address()));
        assertThat(d.allowed()).isFalse();
        assertThat(d.violations()).extracting(PolicyDecision.Violation::rule).containsExactly(PolicyEngine.RULE_COOLDOWN);
        PolicyDecision later = engine(defaults()).evaluate(proposal(w, new BigDecimal("50"), true, true), w, Optional.of(NOW.minusSeconds(120)), Optional.of(w.address()));
        assertThat(later.allowed()).isTrue();
    }

    @Test
    void emergencyStopKeepsProposalsApprovableButNeverExecutable() {
        Wallet w = Fixtures.wallet(SolanaCluster.DEVNET);
        PolicyProperties stopped = new PolicyProperties(false, "devnet", new BigDecimal("500"), new BigDecimal("50"), List.of("SOL", "USDC"),
                Duration.ofSeconds(60), Fixtures.VAULT, 2_000_000_000L, Duration.ofMinutes(30));
        PolicyDecision d = engine(stopped).evaluate(proposal(w, new BigDecimal("50"), true, true), w, Optional.empty(), Optional.of(w.address()));
        assertThat(d.allowed()).isTrue();
        assertThat(d.executable()).isFalse();
        assertThat(d.executionViolations()).extracting(PolicyDecision.Violation::rule).contains(PolicyEngine.RULE_KILL_SWITCH);
    }

    @Test
    void failedSimulationOrMissingTransactionIsNotExecutable() {
        Wallet w = Fixtures.wallet(SolanaCluster.DEVNET);
        PolicyDecision noTx = engine(defaults()).evaluate(proposal(w, new BigDecimal("50"), false, true), w, Optional.empty(), Optional.of(w.address()));
        assertThat(noTx.executable()).isFalse();
        PolicyDecision simFail = engine(defaults()).evaluate(proposal(w, new BigDecimal("50"), true, false), w, Optional.empty(), Optional.of(w.address()));
        assertThat(simFail.executable()).isFalse();
        assertThat(simFail.executionViolations()).extracting(PolicyDecision.Violation::rule).contains(PolicyEngine.RULE_SIMULATION);
    }

    @Test
    void lamportCapAndMissingVaultAreExecutionViolations() {
        Wallet w = Fixtures.wallet(SolanaCluster.DEVNET);
        PolicyProperties tinyCap = new PolicyProperties(true, "devnet", new BigDecimal("500"), new BigDecimal("50"), List.of("SOL", "USDC"),
                Duration.ofSeconds(60), "", 1_000L, Duration.ofMinutes(30));
        PolicyDecision d = engine(tinyCap).evaluate(proposal(w, new BigDecimal("50"), true, true), w, Optional.empty(), Optional.of(w.address()));
        assertThat(d.executionViolations()).extracting(PolicyDecision.Violation::rule).contains(PolicyEngine.RULE_MAX_LAMPORTS, PolicyEngine.RULE_VAULT);
    }

    @Test
    void executionPreconditionsRequireApprovedAndUnexpired() {
        Wallet w = Fixtures.wallet(SolanaCluster.DEVNET);
        ActionProposal p = proposal(w, new BigDecimal("50"), true, true);
        List<String> problems = engine(defaults()).executionPreconditions(p);
        assertThat(problems).anyMatch(s -> s.contains("not APPROVED")).anyMatch(s -> s.contains("No approval record"));
    }
}
