package io.lifeengine.cryptobot.application.controlplane;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.lifeengine.cryptobot.adapters.marketdata.MarketDataProperties;
import io.lifeengine.cryptobot.adapters.marketdata.TokenRegistry;
import io.lifeengine.cryptobot.adapters.solana.SolanaCluster;
import io.lifeengine.cryptobot.application.controlplane.PolicyEngine.WalletState;
import io.lifeengine.cryptobot.domain.policy.PolicyDecision;
import io.lifeengine.cryptobot.domain.policy.PolicyInput;
import io.lifeengine.cryptobot.domain.policy.PolicyPredicate;
import io.lifeengine.cryptobot.domain.policy.PolicyVerdict;
import io.lifeengine.cryptobot.domain.strategy.RebalanceIntent;
import io.lifeengine.cryptobot.domain.strategy.RebalancePlan;
import io.lifeengine.cryptobot.domain.transactions.ActionProposal;
import io.lifeengine.cryptobot.domain.transactions.ApprovalRecord;
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
        return engine(props, authorization());
    }

    private static PolicyEngine engine(PolicyProperties props, AuthorizationProperties auth) {
        return new PolicyEngine(props, auth, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static PolicyProperties defaults() {
        return new PolicyProperties(true, "devnet", new BigDecimal("500"), new BigDecimal("50"), List.of("SOL", "USDC", "USDT"),
                Duration.ofSeconds(60), Fixtures.VAULT, 2_000_000_000L, Duration.ofMinutes(30));
    }

    private static AuthorizationProperties authorization() {
        return new AuthorizationProperties("test-policy-v1", new BigDecimal("100"), new BigDecimal("250"), new BigDecimal("2500"),
                8_000, 100, 50, Duration.ofMinutes(15), List.of("REBALANCE"));
    }

    /** Prices as of now, nothing executed: the state every legacy test assumed. */
    private static WalletState fresh() {
        return WalletState.fresh(NOW);
    }

    private ActionProposal proposal(Wallet wallet, BigDecimal targetSolPct, boolean withTx, boolean simOk) {
        RebalancePlan plan = planner.plan(Fixtures.solHeavy(), new RebalanceIntent(Map.of("SOL", targetSolPct), "USDC"));
        long lamports = plan.legs().get(0).amount().multiply(new BigDecimal("1000000000")).longValue();
        PreparedTransaction tx = withTx ? new PreparedTransaction(wallet.cluster().id(), wallet.address(), Fixtures.VAULT, lamports, "bh", 1L, "AA==", "AA==", "transfer") : null;
        SimulationOutcome sim = new SimulationOutcome(null, new SimulationOutcome.Onchain(simOk, simOk ? null : "boom", 150L, List.of(), wallet.cluster().id()));
        return new ActionProposal(UUID.randomUUID(), wallet.id(), wallet.ownerUserId(), wallet.address(), wallet.cluster().id(), ProposalStatus.SIMULATED,
                "REBALANCE", "t", null, "tester", new RebalanceIntent(Map.of("SOL", targetSolPct), "USDC"), plan, null, null, null, sim, tx, null, null,
                null, null, NOW.plusSeconds(1800), NOW, NOW, null, 0);
    }

    @Test
    void happyPathOnDevnetWithSignerIsAllowedAndExecutable() {
        Wallet w = Fixtures.wallet(SolanaCluster.DEVNET);
        PolicyDecision d = engine(defaults()).evaluate(proposal(w, new BigDecimal("50"), true, true), w, fresh(), Optional.of(w.address()));
        assertThat(d.allowed()).isTrue();
        assertThat(d.executable()).isTrue();
        assertThat(d.violations()).isEmpty();
        assertThat(d.executionViolations()).isEmpty();
        assertThat(d.rulesApplied()).contains(PolicyEngine.RULE_MAX_TRADE_USD, PolicyEngine.RULE_SIGNER, PolicyEngine.RULE_CLUSTER, PolicyEngine.RULE_AUTHORIZATION);
    }

    @Test
    void mainnetWalletIsPaperTradeOnly() {
        Wallet w = Fixtures.wallet(SolanaCluster.MAINNET_BETA);
        PolicyDecision d = engine(defaults()).evaluate(proposal(w, new BigDecimal("50"), true, true), w, fresh(), Optional.of("someoneElse"));
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
        // the tiers must fit under the $100 cap for the policy to load
        AuthorizationProperties auth = new AuthorizationProperties("v", new BigDecimal("10"), new BigDecimal("50"), new BigDecimal("2500"),
                8_000, 100, 50, Duration.ofMinutes(15), List.of("REBALANCE"));
        PolicyDecision d = engine(tight, auth).evaluate(proposal(w, new BigDecimal("50"), true, true), w, fresh(), Optional.of(w.address()));
        assertThat(d.allowed()).isFalse();
        // KAN-436: one cap, two names — the legacy rule and the deterministic predicate both fire on it
        assertThat(d.violations()).extracting(PolicyDecision.Violation::rule).containsExactly(PolicyEngine.RULE_MAX_TRADE_USD, PolicyEngine.RULE_AUTHORIZATION);
        assertThat(d.authorization().failedPredicates()).containsExactly(PolicyPredicate.TRADE_WITHIN_MAX);
    }

    @Test
    void tradeOverThePortfolioPercentageIsBlocked() {
        Wallet w = Fixtures.wallet(SolanaCluster.DEVNET);
        // SOL 70 → 10 moves 60% of the portfolio, over the 50% cap (and $600 > $500)
        PolicyDecision d = engine(defaults()).evaluate(proposal(w, new BigDecimal("10"), true, true), w, fresh(), Optional.of(w.address()));
        assertThat(d.allowed()).isFalse();
        assertThat(d.violations()).extracting(PolicyDecision.Violation::rule).contains(PolicyEngine.RULE_MAX_TRADE_PCT, PolicyEngine.RULE_MAX_TRADE_USD);
    }

    @Test
    void assetOutsideAllowlistIsBlocked() {
        Wallet w = Fixtures.wallet(SolanaCluster.DEVNET);
        PolicyProperties noSol = new PolicyProperties(true, "devnet", new BigDecimal("500"), new BigDecimal("50"), List.of("USDC"),
                Duration.ofSeconds(60), Fixtures.VAULT, 2_000_000_000L, Duration.ofMinutes(30));
        PolicyDecision d = engine(noSol).evaluate(proposal(w, new BigDecimal("50"), true, true), w, fresh(), Optional.of(w.address()));
        assertThat(d.allowed()).isFalse();
        assertThat(d.violations()).extracting(PolicyDecision.Violation::rule).contains(PolicyEngine.RULE_ASSET_ALLOWLIST);
    }

    @Test
    void cooldownBlocksBackToBackExecutions() {
        Wallet w = Fixtures.wallet(SolanaCluster.DEVNET);
        WalletState justTraded = new WalletState(Optional.of(NOW.minusSeconds(10)), BigDecimal.ZERO, NOW);
        PolicyDecision d = engine(defaults()).evaluate(proposal(w, new BigDecimal("50"), true, true), w, justTraded, Optional.of(w.address()));
        assertThat(d.allowed()).isFalse();
        assertThat(d.violations()).extracting(PolicyDecision.Violation::rule).containsExactly(PolicyEngine.RULE_COOLDOWN);
        WalletState twoMinutesAgo = new WalletState(Optional.of(NOW.minusSeconds(120)), BigDecimal.ZERO, NOW);
        PolicyDecision later = engine(defaults()).evaluate(proposal(w, new BigDecimal("50"), true, true), w, twoMinutesAgo, Optional.of(w.address()));
        assertThat(later.allowed()).isTrue();
    }

    @Test
    void emergencyStopKeepsProposalsApprovableButNeverExecutable() {
        Wallet w = Fixtures.wallet(SolanaCluster.DEVNET);
        PolicyProperties stopped = new PolicyProperties(false, "devnet", new BigDecimal("500"), new BigDecimal("50"), List.of("SOL", "USDC"),
                Duration.ofSeconds(60), Fixtures.VAULT, 2_000_000_000L, Duration.ofMinutes(30));
        PolicyDecision d = engine(stopped).evaluate(proposal(w, new BigDecimal("50"), true, true), w, fresh(), Optional.of(w.address()));
        assertThat(d.allowed()).isTrue();
        assertThat(d.executable()).isFalse();
        assertThat(d.executionViolations()).extracting(PolicyDecision.Violation::rule).contains(PolicyEngine.RULE_KILL_SWITCH);
    }

    @Test
    void failedSimulationOrMissingTransactionIsNotExecutable() {
        Wallet w = Fixtures.wallet(SolanaCluster.DEVNET);
        PolicyDecision noTx = engine(defaults()).evaluate(proposal(w, new BigDecimal("50"), false, true), w, fresh(), Optional.of(w.address()));
        assertThat(noTx.executable()).isFalse();
        PolicyDecision simFail = engine(defaults()).evaluate(proposal(w, new BigDecimal("50"), true, false), w, fresh(), Optional.of(w.address()));
        assertThat(simFail.executable()).isFalse();
        assertThat(simFail.executionViolations()).extracting(PolicyDecision.Violation::rule).contains(PolicyEngine.RULE_SIMULATION);
    }

    @Test
    void lamportCapAndMissingVaultAreExecutionViolations() {
        Wallet w = Fixtures.wallet(SolanaCluster.DEVNET);
        PolicyProperties tinyCap = new PolicyProperties(true, "devnet", new BigDecimal("500"), new BigDecimal("50"), List.of("SOL", "USDC"),
                Duration.ofSeconds(60), "", 1_000L, Duration.ofMinutes(30));
        PolicyDecision d = engine(tinyCap).evaluate(proposal(w, new BigDecimal("50"), true, true), w, fresh(), Optional.of(w.address()));
        assertThat(d.executionViolations()).extracting(PolicyDecision.Violation::rule).contains(PolicyEngine.RULE_MAX_LAMPORTS, PolicyEngine.RULE_VAULT);
    }

    @Test
    void executionPreconditionsRequireApprovedAndUnexpired() {
        Wallet w = Fixtures.wallet(SolanaCluster.DEVNET);
        ActionProposal p = proposal(w, new BigDecimal("50"), true, true);
        List<String> problems = engine(defaults()).executionPreconditions(p);
        assertThat(problems).anyMatch(s -> s.contains("not APPROVED")).anyMatch(s -> s.contains("No approval record"));
    }

    // ---- KAN-436: the deterministic verdict wired into the flow -----------------------------

    @Test
    void verdictIsRecordedWithThePolicyHashAndTheTier() {
        Wallet w = Fixtures.wallet(SolanaCluster.DEVNET);
        PolicyEngine engine = engine(defaults());
        // SOL 70 → 50 on a $1000 wallet sells $200: over the $100 autonomous tier, within the $250 second-agent tier
        PolicyDecision d = engine.evaluate(proposal(w, new BigDecimal("50"), true, true), w, fresh(), Optional.of(w.address()));
        PolicyVerdict v = d.authorization();
        assertThat(v).isNotNull();
        assertThat(v.decision()).isEqualTo(PolicyVerdict.Decision.ESCALATE);
        assertThat(v.escalation()).isEqualTo(PolicyVerdict.Escalation.REQUIRE_SECOND_AGENT);
        assertThat(v.tier()).isEqualTo(PolicyVerdict.AutonomyTier.SECOND_AGENT);
        assertThat(v.failedPredicates()).isEmpty();
        assertThat(v.evaluatedPredicates()).containsExactly(PolicyPredicate.values());
        assertThat(v.policyVersion()).isEqualTo("test-policy-v1");
        assertThat(v.policyHash()).isEqualTo(engine.rules().hash()).startsWith("sha256:");
        assertThat(v.inputHash()).startsWith("sha256:");
        assertThat(d.allowed()).isTrue();
    }

    @Test
    void policyInputIsDerivedFromTheProposalNotDefaulted() {
        Wallet w = Fixtures.wallet(SolanaCluster.DEVNET);
        PolicyEngine engine = engine(defaults());
        ActionProposal p = proposal(w, new BigDecimal("50"), true, true);
        PolicyInput in = engine.policyInput(p, w, new WalletState(Optional.empty(), new BigDecimal("12.345"), NOW.minusSeconds(90)), NOW);
        assertThat(in.intent().agentId()).isEqualTo("tester");
        assertThat(in.intent().strategyId()).isEqualTo("REBALANCE");
        assertThat(in.intent().policyVersion()).isEqualTo("test-policy-v1");
        assertThat(in.intent().asset()).isEqualTo("SOL"); // no BUY leg ⇒ the largest SELL leg
        assertThat(in.intent().tradeValueCents()).isEqualTo(20_000L);
        assertThat(in.intent().maxSlippageBps()).isEqualTo(50);
        assertThat(in.intent().validUntilSlot()).isEqualTo(NOW.plusSeconds(1800).getEpochSecond());
        assertThat(in.state().dailyExposureCents()).isEqualTo(1_235L); // rounded up, never down
        assertThat(in.state().assetExposureAfterBps()).isEqualTo(5_000);
        assertThat(in.state().oracleAgeSeconds()).isEqualTo(90L);
        assertThat(in.state().agentPermitted()).isTrue();
        assertThat(in.state().nonceUnused()).isTrue();
        assertThat(in.state().currentSlot()).isEqualTo(NOW.getEpochSecond());
    }

    @Test
    void dailyLimitDeniesAndBlocksTheProposal() {
        Wallet w = Fixtures.wallet(SolanaCluster.DEVNET);
        WalletState heavyDay = new WalletState(Optional.of(NOW.minusSeconds(3600)), new BigDecimal("2400"), NOW);
        PolicyDecision d = engine(defaults()).evaluate(proposal(w, new BigDecimal("50"), true, true), w, heavyDay, Optional.of(w.address()));
        assertThat(d.allowed()).isFalse();
        assertThat(d.authorization().decision()).isEqualTo(PolicyVerdict.Decision.DENY);
        assertThat(d.authorization().failedPredicates()).containsExactly(PolicyPredicate.DAILY_LIMIT);
        assertThat(d.violations()).extracting(PolicyDecision.Violation::rule).containsExactly(PolicyEngine.RULE_AUTHORIZATION);
        assertThat(d.violations().get(0).message()).contains("DAILY_LIMIT").contains("sha256:");
    }

    @Test
    void staleOracleDeniesFailClosed() {
        Wallet w = Fixtures.wallet(SolanaCluster.DEVNET);
        WalletState stale = new WalletState(Optional.empty(), BigDecimal.ZERO, NOW.minus(Duration.ofMinutes(16)));
        PolicyDecision d = engine(defaults()).evaluate(proposal(w, new BigDecimal("50"), true, true), w, stale, Optional.of(w.address()));
        assertThat(d.allowed()).isFalse();
        assertThat(d.authorization().failedPredicates()).containsExactly(PolicyPredicate.ORACLE_FRESH);

        WalletState unknownAge = new WalletState(Optional.empty(), BigDecimal.ZERO, null);
        PolicyDecision u = engine(defaults()).evaluate(proposal(w, new BigDecimal("50"), true, true), w, unknownAge, Optional.of(w.address()));
        assertThat(u.allowed()).isFalse();
        assertThat(u.authorization().failedPredicates()).containsExactly(PolicyPredicate.ORACLE_FRESH);
    }

    @Test
    void executorSlippageAboveThePolicyCapDeniesEverything() {
        Wallet w = Fixtures.wallet(SolanaCluster.DEVNET);
        AuthorizationProperties sloppy = new AuthorizationProperties("v", new BigDecimal("100"), new BigDecimal("250"), new BigDecimal("2500"),
                8_000, 100, 150, Duration.ofMinutes(15), List.of("REBALANCE"));
        PolicyDecision d = engine(defaults(), sloppy).evaluate(proposal(w, new BigDecimal("50"), true, true), w, fresh(), Optional.of(w.address()));
        assertThat(d.allowed()).isFalse();
        assertThat(d.authorization().failedPredicates()).containsExactly(PolicyPredicate.SLIPPAGE_WITHIN_MAX);
    }

    @Test
    void policyThatCannotBeLoadedStopsTheEngine() {
        AuthorizationProperties inverted = new AuthorizationProperties("v", new BigDecimal("300"), new BigDecimal("250"), new BigDecimal("2500"),
                8_000, 100, 50, Duration.ofMinutes(15), List.of("REBALANCE"));
        assertThatThrownBy(() -> engine(defaults(), inverted)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("autonomous_up_to_cents");
        AuthorizationProperties overCap = new AuthorizationProperties("v", new BigDecimal("100"), new BigDecimal("600"), new BigDecimal("2500"),
                8_000, 100, 50, Duration.ofMinutes(15), List.of("REBALANCE"));
        assertThatThrownBy(() -> engine(defaults(), overCap)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("second_agent_up_to_cents");
    }

    @Test
    void executionRefusesAProposalDecidedUnderAnotherPolicy() {
        Wallet w = Fixtures.wallet(SolanaCluster.DEVNET);
        PolicyEngine v1 = engine(defaults());
        ActionProposal p = proposal(w, new BigDecimal("50"), true, true);
        PolicyDecision decided = v1.evaluate(p, w, fresh(), Optional.of(w.address()));
        ActionProposal approved = p.withPolicy(decided, NOW)
                .withStatus(ProposalStatus.AWAITING_APPROVAL, NOW)
                .withApproval(new ApprovalRecord(ApprovalRecord.Decision.APPROVED, "op", NOW, null, NOW), NOW)
                .withStatus(ProposalStatus.APPROVED, NOW);
        assertThat(v1.executionPreconditions(approved)).isEmpty();

        AuthorizationProperties v2 = new AuthorizationProperties("test-policy-v2", new BigDecimal("100"), new BigDecimal("250"), new BigDecimal("2000"),
                8_000, 100, 50, Duration.ofMinutes(15), List.of("REBALANCE"));
        assertThat(engine(defaults(), v2).executionPreconditions(approved)).singleElement().asString().contains("Policy changed since evaluation");

        ActionProposal legacy = approved.withPolicy(new PolicyDecision(true, true, List.of(), List.of(), List.of(), NOW, null), NOW);
        assertThat(v1.executionPreconditions(legacy)).singleElement().asString().contains("No policy verdict");

        // KAN-438: a verdict without its recorded (I, S) cannot be re-derived by the validator.
        ActionProposal noInput = approved.withPolicy(new PolicyDecision(true, true, List.of(), List.of(), List.of(), NOW, decided.authorization()), NOW);
        assertThat(v1.executionPreconditions(noInput)).singleElement().asString().contains("No recorded (I, S)");
    }

    // ---- KAN-438: independent validator + timelock (paper §19, §20) ---------------------------

    private static io.lifeengine.cryptobot.integration.validator.ValidatorClient.Identity validator(String hash, boolean enabled) {
        return new io.lifeengine.cryptobot.integration.validator.ValidatorClient.Identity("vkey", "test-policy-v1", hash, true, enabled);
    }

    @Test
    void executableOnlyWithAValidatorOnTheSamePolicy() {
        Wallet w = Fixtures.wallet(SolanaCluster.DEVNET);
        PolicyEngine engine = engine(defaults());
        PolicyDecision decided = engine.evaluate(proposal(w, new BigDecimal("50"), true, true), w, fresh(), Optional.of(w.address()));
        assertThat(decided.executable()).isTrue();
        assertThat(decided.input()).isNotNull();
        assertThat(decided.input().hash()).isEqualTo(decided.authorization().inputHash());

        PolicyDecision none = engine.requireValidator(decided, Optional.empty());
        assertThat(none.allowed()).isTrue();
        assertThat(none.executable()).isFalse();
        assertThat(none.executionViolations()).extracting(PolicyDecision.Violation::rule).containsExactly(PolicyEngine.RULE_VALIDATOR);

        PolicyDecision other = engine.requireValidator(decided, Optional.of(validator("sha256:" + "0".repeat(64), true)));
        assertThat(other.executable()).isFalse();
        assertThat(other.executionViolations().get(0).message()).contains("Validator holds policy");

        PolicyDecision stopped = engine.requireValidator(decided, Optional.of(validator(engine.rules().hash(), false)));
        assertThat(stopped.executable()).isFalse();

        PolicyDecision ok = engine.requireValidator(decided, Optional.of(validator(engine.rules().hash(), true)));
        assertThat(ok.executable()).isTrue();
        assertThat(ok.rulesApplied()).contains(PolicyEngine.RULE_VALIDATOR);
        assertThat(ok.input()).isEqualTo(decided.input());
    }

    @Test
    void timelockByTierAndExecutionWaitsForIt() {
        Wallet w = Fixtures.wallet(SolanaCluster.DEVNET);
        TimelockProperties locks = new TimelockProperties(Duration.ZERO, Duration.ofMinutes(30), Duration.ofMinutes(30));
        PolicyEngine engine = new PolicyEngine(defaults(), authorization(), locks, Clock.fixed(NOW, ZoneOffset.UTC));
        ActionProposal p = proposal(w, new BigDecimal("50"), true, true);
        PolicyDecision decided = engine.evaluate(p, w, fresh(), Optional.of(w.address()));
        // The $50-target plan on the SOL-heavy fixture is above the $100 autonomous tier: ESCALATE ⇒ 30 min.
        assertThat(decided.authorization().decision()).isEqualTo(PolicyVerdict.Decision.ESCALATE);
        assertThat(engine.executableAt(NOW, decided)).isEqualTo(NOW.plus(Duration.ofMinutes(30)));

        ActionProposal approved = p.withPolicy(decided, NOW).withStatus(ProposalStatus.AWAITING_APPROVAL, NOW)
                .withApproval(new ApprovalRecord(ApprovalRecord.Decision.APPROVED, "op", NOW, null, engine.executableAt(NOW, decided)), NOW)
                .withStatus(ProposalStatus.APPROVED, NOW).withExpiresAt(NOW.plus(Duration.ofHours(2)), NOW);
        assertThat(engine.executionPreconditions(approved)).singleElement().asString().contains("Timelock").contains("1800s remaining");

        // 30 minutes later the same row executes.
        PolicyEngine later = new PolicyEngine(defaults(), authorization(), locks, Clock.fixed(NOW.plus(Duration.ofMinutes(30)), ZoneOffset.UTC));
        assertThat(later.executionPreconditions(approved)).isEmpty();

        // An ALLOW-tier verdict has no lock.
        PolicyVerdict allow = new PolicyVerdict(PolicyVerdict.Decision.ALLOW, PolicyVerdict.Escalation.NONE, PolicyVerdict.AutonomyTier.AUTONOMOUS,
                List.of(), List.of(PolicyPredicate.values()), "test-policy-v1", engine.rules().hash(), decided.authorization().inputHash());
        PolicyDecision small = new PolicyDecision(true, true, List.of(), List.of(), List.of(), NOW, allow, decided.input());
        assertThat(engine.executableAt(NOW, small)).isEqualTo(NOW);

        // A row approved before KAN-438 (no executableAt) derives its lock from the approval time.
        ActionProposal legacy = approved.withApproval(new ApprovalRecord(ApprovalRecord.Decision.APPROVED, "op", NOW.minus(Duration.ofMinutes(10)), null), NOW);
        assertThat(engine.executableAt(legacy)).isEqualTo(NOW.plus(Duration.ofMinutes(20)));
        assertThat(engine.executionPreconditions(legacy)).singleElement().asString().contains("1200s remaining");
    }
}
