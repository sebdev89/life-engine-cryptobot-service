package io.lifeengine.cryptobot.application.controlplane;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.lifeengine.cryptobot.adapters.marketdata.MarketDataProperties;
import io.lifeengine.cryptobot.adapters.marketdata.TokenRegistry;
import io.lifeengine.cryptobot.solana.rpc.SolanaCluster;
import io.lifeengine.cryptobot.application.controlplane.PolicyEngine.WalletState;
import io.lifeengine.cryptobot.core.oracle.OracleConsensus;
import io.lifeengine.cryptobot.core.oracle.OracleReading;
import io.lifeengine.cryptobot.core.oracle.PriceObservation;
import io.lifeengine.cryptobot.core.oracle.PriceOracle;
import io.lifeengine.cryptobot.core.policy.PolicyDecision;
import io.lifeengine.cryptobot.core.policy.PolicyInput;
import io.lifeengine.cryptobot.core.policy.PolicyPredicate;
import io.lifeengine.cryptobot.core.policy.PolicyVerdict;
import io.lifeengine.cryptobot.trading.strategy.RebalanceIntent;
import io.lifeengine.cryptobot.trading.strategy.RebalancePlan;
import io.lifeengine.cryptobot.core.execution.ActionProposal;
import io.lifeengine.cryptobot.core.execution.ApprovalRecord;
import io.lifeengine.cryptobot.core.execution.PreparedTransaction;
import io.lifeengine.cryptobot.core.execution.ProposalStatus;
import io.lifeengine.cryptobot.core.execution.SimulationOutcome;
import io.lifeengine.cryptobot.core.wallet.Wallet;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import io.lifeengine.cryptobot.integration.signer.SignerClient;
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

    /** KAN-572: a signer on devnet that controls {@code publicKey}, with the demo's caps and the vault allowlisted. */
    static Optional<SignerClient.Identity> signer(String publicKey) {
        return Optional.of(new SignerClient.Identity(publicKey, "devnet", 2_000_000_000L, List.of(Fixtures.VAULT)));
    }

    /** Prices as of now, nothing executed, the oracle agreeing with the snapshot: the state every legacy test assumed. */
    private static WalletState fresh() {
        return WalletState.fresh(NOW, Fixtures.oracle());
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
        PolicyDecision d = engine(defaults()).evaluate(proposal(w, new BigDecimal("50"), true, true), w, fresh(), signer(w.address()));
        assertThat(d.allowed()).isTrue();
        assertThat(d.executable()).isTrue();
        assertThat(d.violations()).isEmpty();
        assertThat(d.executionViolations()).isEmpty();
        assertThat(d.rulesApplied()).contains(PolicyEngine.RULE_MAX_TRADE_USD, PolicyEngine.RULE_SIGNER, PolicyEngine.RULE_CLUSTER, PolicyEngine.RULE_AUTHORIZATION);
    }

    @Test
    void mainnetWalletIsPaperTradeOnly() {
        Wallet w = Fixtures.wallet(SolanaCluster.MAINNET_BETA);
        PolicyDecision d = engine(defaults()).evaluate(proposal(w, new BigDecimal("50"), true, true), w, fresh(), signer("someoneElse"));
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
        PolicyDecision d = engine(tight, auth).evaluate(proposal(w, new BigDecimal("50"), true, true), w, fresh(), signer(w.address()));
        assertThat(d.allowed()).isFalse();
        // KAN-436: one cap, two names — the legacy rule and the deterministic predicate both fire on it
        assertThat(d.violations()).extracting(PolicyDecision.Violation::rule).containsExactly(PolicyEngine.RULE_MAX_TRADE_USD, PolicyEngine.RULE_AUTHORIZATION);
        assertThat(d.authorization().failedPredicates()).containsExactly(PolicyPredicate.TRADE_WITHIN_MAX);
    }

    @Test
    void tradeOverThePortfolioPercentageIsBlocked() {
        Wallet w = Fixtures.wallet(SolanaCluster.DEVNET);
        // SOL 70 → 10 moves 60% of the portfolio, over the 50% cap (and $600 > $500)
        PolicyDecision d = engine(defaults()).evaluate(proposal(w, new BigDecimal("10"), true, true), w, fresh(), signer(w.address()));
        assertThat(d.allowed()).isFalse();
        assertThat(d.violations()).extracting(PolicyDecision.Violation::rule).contains(PolicyEngine.RULE_MAX_TRADE_PCT, PolicyEngine.RULE_MAX_TRADE_USD);
    }

    @Test
    void assetOutsideAllowlistIsBlocked() {
        Wallet w = Fixtures.wallet(SolanaCluster.DEVNET);
        PolicyProperties noSol = new PolicyProperties(true, "devnet", new BigDecimal("500"), new BigDecimal("50"), List.of("USDC"),
                Duration.ofSeconds(60), Fixtures.VAULT, 2_000_000_000L, Duration.ofMinutes(30));
        PolicyDecision d = engine(noSol).evaluate(proposal(w, new BigDecimal("50"), true, true), w, fresh(), signer(w.address()));
        assertThat(d.allowed()).isFalse();
        assertThat(d.violations()).extracting(PolicyDecision.Violation::rule).contains(PolicyEngine.RULE_ASSET_ALLOWLIST);
    }

    @Test
    void cooldownBlocksBackToBackExecutions() {
        Wallet w = Fixtures.wallet(SolanaCluster.DEVNET);
        WalletState justTraded = new WalletState(Optional.of(NOW.minusSeconds(10)), BigDecimal.ZERO, NOW, Fixtures.oracle());
        PolicyDecision d = engine(defaults()).evaluate(proposal(w, new BigDecimal("50"), true, true), w, justTraded, signer(w.address()));
        assertThat(d.allowed()).isFalse();
        assertThat(d.violations()).extracting(PolicyDecision.Violation::rule).containsExactly(PolicyEngine.RULE_COOLDOWN);
        WalletState twoMinutesAgo = new WalletState(Optional.of(NOW.minusSeconds(120)), BigDecimal.ZERO, NOW, Fixtures.oracle());
        PolicyDecision later = engine(defaults()).evaluate(proposal(w, new BigDecimal("50"), true, true), w, twoMinutesAgo, signer(w.address()));
        assertThat(later.allowed()).isTrue();
    }

    @Test
    void emergencyStopKeepsProposalsApprovableButNeverExecutable() {
        Wallet w = Fixtures.wallet(SolanaCluster.DEVNET);
        PolicyProperties stopped = new PolicyProperties(false, "devnet", new BigDecimal("500"), new BigDecimal("50"), List.of("SOL", "USDC"),
                Duration.ofSeconds(60), Fixtures.VAULT, 2_000_000_000L, Duration.ofMinutes(30));
        PolicyDecision d = engine(stopped).evaluate(proposal(w, new BigDecimal("50"), true, true), w, fresh(), signer(w.address()));
        assertThat(d.allowed()).isTrue();
        assertThat(d.executable()).isFalse();
        assertThat(d.executionViolations()).extracting(PolicyDecision.Violation::rule).contains(PolicyEngine.RULE_KILL_SWITCH);
    }

    @Test
    void failedSimulationOrMissingTransactionIsNotExecutable() {
        Wallet w = Fixtures.wallet(SolanaCluster.DEVNET);
        PolicyDecision noTx = engine(defaults()).evaluate(proposal(w, new BigDecimal("50"), false, true), w, fresh(), signer(w.address()));
        assertThat(noTx.executable()).isFalse();
        PolicyDecision simFail = engine(defaults()).evaluate(proposal(w, new BigDecimal("50"), true, false), w, fresh(), signer(w.address()));
        assertThat(simFail.executable()).isFalse();
        assertThat(simFail.executionViolations()).extracting(PolicyDecision.Violation::rule).contains(PolicyEngine.RULE_SIMULATION);
    }

    @Test
    void lamportCapAndMissingVaultAreExecutionViolations() {
        Wallet w = Fixtures.wallet(SolanaCluster.DEVNET);
        PolicyProperties tinyCap = new PolicyProperties(true, "devnet", new BigDecimal("500"), new BigDecimal("50"), List.of("SOL", "USDC"),
                Duration.ofSeconds(60), "", 1_000L, Duration.ofMinutes(30));
        PolicyDecision d = engine(tinyCap).evaluate(proposal(w, new BigDecimal("50"), true, true), w, fresh(), signer(w.address()));
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
        PolicyDecision d = engine.evaluate(proposal(w, new BigDecimal("50"), true, true), w, fresh(), signer(w.address()));
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
        PolicyInput in = engine.policyInput(p, w, new WalletState(Optional.empty(), new BigDecimal("12.345"), NOW.minusSeconds(90), Fixtures.oracle("100", NOW.minusSeconds(20))), NOW);
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
        WalletState heavyDay = new WalletState(Optional.of(NOW.minusSeconds(3600)), new BigDecimal("2400"), NOW, Fixtures.oracle());
        PolicyDecision d = engine(defaults()).evaluate(proposal(w, new BigDecimal("50"), true, true), w, heavyDay, signer(w.address()));
        assertThat(d.allowed()).isFalse();
        assertThat(d.authorization().decision()).isEqualTo(PolicyVerdict.Decision.DENY);
        assertThat(d.authorization().failedPredicates()).containsExactly(PolicyPredicate.DAILY_LIMIT);
        assertThat(d.violations()).extracting(PolicyDecision.Violation::rule).containsExactly(PolicyEngine.RULE_AUTHORIZATION);
        assertThat(d.violations().get(0).message()).contains("DAILY_LIMIT").contains("sha256:");
    }

    @Test
    void staleOracleDeniesFailClosed() {
        Wallet w = Fixtures.wallet(SolanaCluster.DEVNET);
        WalletState stale = new WalletState(Optional.empty(), BigDecimal.ZERO, NOW.minus(Duration.ofMinutes(16)), Fixtures.oracle());
        PolicyDecision d = engine(defaults()).evaluate(proposal(w, new BigDecimal("50"), true, true), w, stale, signer(w.address()));
        assertThat(d.allowed()).isFalse();
        assertThat(d.authorization().failedPredicates()).containsExactly(PolicyPredicate.ORACLE_FRESH);

        WalletState unknownAge = new WalletState(Optional.empty(), BigDecimal.ZERO, null, Fixtures.oracle());
        PolicyDecision u = engine(defaults()).evaluate(proposal(w, new BigDecimal("50"), true, true), w, unknownAge, signer(w.address()));
        assertThat(u.allowed()).isFalse();
        assertThat(u.authorization().failedPredicates()).containsExactly(PolicyPredicate.ORACLE_FRESH);

        // KAN-439: a consensus whose oldest observation is older than max-oracle-age is just as stale as an old snapshot
        WalletState oldConsensus = new WalletState(Optional.empty(), BigDecimal.ZERO, NOW, Fixtures.oracle("100", NOW.minus(Duration.ofMinutes(16))));
        PolicyDecision oc = engine(defaults()).evaluate(proposal(w, new BigDecimal("50"), true, true), w, oldConsensus, signer(w.address()));
        assertThat(oc.allowed()).isFalse();
        assertThat(oc.authorization().failedPredicates()).containsExactly(PolicyPredicate.ORACLE_FRESH);
    }

    @Test
    void executorSlippageAboveThePolicyCapDeniesEverything() {
        Wallet w = Fixtures.wallet(SolanaCluster.DEVNET);
        AuthorizationProperties sloppy = new AuthorizationProperties("v", new BigDecimal("100"), new BigDecimal("250"), new BigDecimal("2500"),
                8_000, 100, 150, Duration.ofMinutes(15), List.of("REBALANCE"));
        PolicyDecision d = engine(defaults(), sloppy).evaluate(proposal(w, new BigDecimal("50"), true, true), w, fresh(), signer(w.address()));
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
        PolicyDecision decided = v1.evaluate(p, w, fresh(), signer(w.address()));
        ActionProposal approved = p.withPolicy(decided, NOW)
                .withStatus(ProposalStatus.AWAITING_APPROVAL, NOW)
                .withApproval(new ApprovalRecord(ApprovalRecord.Decision.APPROVED, "op", NOW, null, NOW), NOW)
                .withStatus(ProposalStatus.APPROVED, NOW);
        assertThat(v1.executionPreconditions(approved)).isEmpty();

        AuthorizationProperties v2 = new AuthorizationProperties("test-policy-v2", new BigDecimal("100"), new BigDecimal("250"), new BigDecimal("2000"),
                8_000, 100, 50, Duration.ofMinutes(15), List.of("REBALANCE"));
        assertThat(engine(defaults(), v2).executionPreconditions(approved)).singleElement().asString().contains("Policy changed since evaluation");

        // A pre-KAN-436 row has neither a verdict nor (pre-KAN-439) an oracle reading: both are named, both refuse.
        ActionProposal legacy = approved.withPolicy(new PolicyDecision(true, true, List.of(), List.of(), List.of(), NOW, null), NOW);
        assertThat(v1.executionPreconditions(legacy)).hasSize(2)
                .anySatisfy(m -> assertThat(m).contains("No policy verdict"))
                .anySatisfy(m -> assertThat(m).contains("No oracle reading"));

        // KAN-438: a verdict without its recorded (I, S) cannot be re-derived by the validator; without a reading (KAN-439) it does not execute either.
        ActionProposal noInput = approved.withPolicy(new PolicyDecision(true, true, List.of(), List.of(), List.of(), NOW, decided.authorization()), NOW);
        assertThat(v1.executionPreconditions(noInput)).hasSize(2)
                .anySatisfy(m -> assertThat(m).contains("No recorded (I, S)"))
                .anySatisfy(m -> assertThat(m).contains("No oracle reading"));
    }

    // ---- KAN-438: independent validator + timelock (paper §19, §20) ---------------------------

    private static io.lifeengine.cryptobot.integration.validator.ValidatorClient.Identity validator(String hash, boolean enabled) {
        return new io.lifeengine.cryptobot.integration.validator.ValidatorClient.Identity("vkey", "test-policy-v1", hash, true, enabled);
    }

    @Test
    void executableOnlyWithAValidatorOnTheSamePolicy() {
        Wallet w = Fixtures.wallet(SolanaCluster.DEVNET);
        PolicyEngine engine = engine(defaults());
        PolicyDecision decided = engine.evaluate(proposal(w, new BigDecimal("50"), true, true), w, fresh(), signer(w.address()));
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
        PolicyDecision decided = engine.evaluate(p, w, fresh(), signer(w.address()));
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

    // ---- KAN-582: each failed precondition carries the bounded reason the 409 is counted under ----

    @Test
    void executionRefusalsCarryTheReasonAndThePrimaryOneIsTheMostSpecific() {
        Wallet w = Fixtures.wallet(SolanaCluster.DEVNET);
        TimelockProperties locks = new TimelockProperties(Duration.ZERO, Duration.ofMinutes(30), Duration.ofMinutes(30));
        PolicyEngine engine = new PolicyEngine(defaults(), authorization(), locks, Clock.fixed(NOW, ZoneOffset.UTC));
        ActionProposal p = proposal(w, new BigDecimal("50"), true, true);
        PolicyDecision decided = engine.evaluate(p, w, fresh(), signer(w.address()));

        // Not yet approved: state, twice (status + no approval record); the messages are the old ones.
        List<PolicyEngine.Refusal> notApproved = engine.executionRefusals(p.withPolicy(decided, NOW));
        assertThat(notApproved).extracting(PolicyEngine.Refusal::reason).containsOnly(io.lifeengine.cryptobot.observability.CryptobotMetrics.RefusalReason.STATE);
        assertThat(engine.executionPreconditions(p.withPolicy(decided, NOW))).containsExactlyElementsOf(notApproved.stream().map(PolicyEngine.Refusal::message).toList());
        assertThat(PolicyEngine.primaryRefusal(notApproved)).isEqualTo(io.lifeengine.cryptobot.observability.CryptobotMetrics.RefusalReason.STATE);

        // Approved inside the timelock: timelock.
        ActionProposal approved = p.withPolicy(decided, NOW).withStatus(ProposalStatus.AWAITING_APPROVAL, NOW)
                .withApproval(new ApprovalRecord(ApprovalRecord.Decision.APPROVED, "op", NOW, null, engine.executableAt(NOW, decided)), NOW)
                .withStatus(ProposalStatus.APPROVED, NOW).withExpiresAt(NOW.plus(Duration.ofHours(2)), NOW);
        List<PolicyEngine.Refusal> locked = engine.executionRefusals(approved);
        assertThat(locked).singleElement().extracting(PolicyEngine.Refusal::reason).isEqualTo(io.lifeengine.cryptobot.observability.CryptobotMetrics.RefusalReason.TIMELOCK);

        // Kill switch on top of the timelock: both reasons are reported; the primary follows the enum order
        // (mainnet > timelock > cooldown > policy > oracle > state), so timelock wins.
        PolicyProperties stopped = new PolicyProperties(false, "devnet", new BigDecimal("500"), new BigDecimal("50"), List.of("SOL", "USDC"),
                Duration.ofSeconds(60), "", 10_000_000_000L, Duration.ofMinutes(30));
        PolicyEngine halted = new PolicyEngine(stopped, authorization(), locks, Clock.fixed(NOW, ZoneOffset.UTC));
        List<PolicyEngine.Refusal> both = halted.executionRefusals(approved);
        assertThat(both).extracting(PolicyEngine.Refusal::reason).contains(
                io.lifeengine.cryptobot.observability.CryptobotMetrics.RefusalReason.POLICY,
                io.lifeengine.cryptobot.observability.CryptobotMetrics.RefusalReason.TIMELOCK);
        assertThat(PolicyEngine.primaryRefusal(both)).isEqualTo(io.lifeengine.cryptobot.observability.CryptobotMetrics.RefusalReason.TIMELOCK);

        // A proposal the cooldown rule blocked at evaluation time: cooldown, not policy, and it beats state.
        PolicyDecision cooled = new PolicyDecision(false, false,
                List.of(new PolicyDecision.Violation(PolicyEngine.RULE_COOLDOWN, "This wallet executed a trade 5s ago; cooldown is 60s")),
                List.of(), decided.rulesApplied(), NOW, decided.authorization(), decided.input(), decided.oracle());
        List<PolicyEngine.Refusal> cooldown = engine.executionRefusals(p.withPolicy(cooled, NOW).withStatus(ProposalStatus.BLOCKED_BY_POLICY, NOW));
        assertThat(cooldown).extracting(PolicyEngine.Refusal::reason).contains(io.lifeengine.cryptobot.observability.CryptobotMetrics.RefusalReason.COOLDOWN);
        assertThat(PolicyEngine.primaryRefusal(cooldown)).isEqualTo(io.lifeengine.cryptobot.observability.CryptobotMetrics.RefusalReason.COOLDOWN);
        assertThat(PolicyEngine.primaryRefusal(List.of())).isEqualTo(io.lifeengine.cryptobot.observability.CryptobotMetrics.RefusalReason.STATE);
    }

    // ---- KAN-439: CorrectRules + CorruptState ⇏ SafeExecution (paper §22) --------------------------

    @Test
    void noOracleReadingDeniesFailClosed() {
        Wallet w = Fixtures.wallet(SolanaCluster.DEVNET);
        WalletState blind = new WalletState(Optional.empty(), BigDecimal.ZERO, NOW, null);
        PolicyDecision d = engine(defaults()).evaluate(proposal(w, new BigDecimal("50"), true, true), w, blind, signer(w.address()));
        assertThat(d.allowed()).isFalse();
        assertThat(d.rulesApplied()).containsAll(PolicyEngine.PRICE_RULES);
        assertThat(d.violations()).extracting(PolicyDecision.Violation::rule).containsExactly(PolicyEngine.RULE_PRICE_QUORUM, PolicyEngine.RULE_AUTHORIZATION);
        assertThat(d.violations().get(0).message()).contains("No oracle reading");
        assertThat(d.authorization().failedPredicates()).containsExactly(PolicyPredicate.ORACLE_FRESH); // unknown age, unknown state
        assertThat(d.oracle()).isNull();
    }

    @Test
    void planBuiltOnACorruptSnapshotIsRefusedByTheWorld() {
        // The snapshot priced SOL at $100 and the plan sells 2 SOL for $200. The oracle — three sources agreeing — says
        // SOL is $1000: the snapshot was wrong by 10×, and a deterministic engine trusting it would authorise selling at 10 % of value.
        Wallet w = Fixtures.wallet(SolanaCluster.DEVNET);
        WalletState world = WalletState.fresh(NOW, Fixtures.oracle("1000", NOW));
        PolicyDecision d = engine(defaults()).evaluate(proposal(w, new BigDecimal("50"), true, true), w, world, signer(w.address()));
        assertThat(d.allowed()).isFalse();
        assertThat(d.violations()).extracting(PolicyDecision.Violation::rule).containsExactly(PolicyEngine.RULE_PRICE_DRIFT);
        assertThat(d.violations().get(0).message()).contains("Plan priced SOL at $100").contains("oracle median is $1000").contains("9000 bps apart");
        // the predicates themselves hold (the state is fresh and within limits): only the integrity rule knows the state is corrupt
        assertThat(d.authorization().denied()).isFalse();
        // within the drift bound the plan stands: SOL at $108 (741 bps) is the market moving, not a corrupt snapshot
        PolicyDecision fine = engine(defaults()).evaluate(proposal(w, new BigDecimal("50"), true, true), w, WalletState.fresh(NOW, Fixtures.oracle("108", NOW)), signer(w.address()));
        assertThat(fine.allowed()).isTrue();
    }

    @Test
    void sourcesThatDisagreeLeaveTheStateUnknownAndDeny() {
        Wallet w = Fixtures.wallet(SolanaCluster.DEVNET);
        // Jupiter says $100, Pyth says $10: no consensus for SOL; USDC is fine
        OracleReading split = new OracleReading(NOW, Fixtures.ORACLE_LIMITS, List.of(
                Fixtures.consensus("SOL", TokenRegistry.NATIVE_SOL_MINT, "100", "10", NOW),
                Fixtures.consensus("USDC", TokenRegistry.USDC_MINT, "1", "1", NOW)));
        assertThat(split.accepted()).isFalse();
        PolicyDecision d = engine(defaults()).evaluate(proposal(w, new BigDecimal("50"), true, true), w, WalletState.fresh(NOW, split), signer(w.address()));
        assertThat(d.allowed()).isFalse();
        assertThat(d.violations()).extracting(PolicyDecision.Violation::rule).containsExactly(PolicyEngine.RULE_PRICE_DEVIATION, PolicyEngine.RULE_AUTHORIZATION);
        assertThat(d.violations().get(0).message()).contains("SOL: sources disagree").contains("jupiter=$100").contains("pyth=$10").contains("bps apart, limit 100");
        assertThat(d.authorization().failedPredicates()).containsExactly(PolicyPredicate.ORACLE_FRESH);
        // an asset the plan needs that the reading does not cover is unknown too
        OracleReading solOnly = new OracleReading(NOW, Fixtures.ORACLE_LIMITS, List.of(Fixtures.consensus("SOL", TokenRegistry.NATIVE_SOL_MINT, "100", "100", NOW)));
        PolicyDecision missing = engine(defaults()).evaluate(proposal(w, new BigDecimal("50"), true, true), w, WalletState.fresh(NOW, solOnly), signer(w.address()));
        assertThat(missing.violations()).extracting(PolicyDecision.Violation::message).anyMatch(m -> m.equals("USDC: not in the oracle reading"));
    }

    @Test
    void decisionCarriesTheReadingAsItsStateReference() {
        Wallet w = Fixtures.wallet(SolanaCluster.DEVNET);
        OracleReading reading = Fixtures.oracle();
        PolicyDecision d = engine(defaults()).evaluate(proposal(w, new BigDecimal("50"), true, true), w, WalletState.fresh(NOW, reading), signer(w.address()));
        assertThat(d.allowed()).isTrue();
        assertThat(d.oracle()).isEqualTo(reading);
        assertThat(d.oracle().quotesHash()).startsWith("sha256:");
        assertThat(d.oracle().limitsHash()).isEqualTo(Fixtures.ORACLE_LIMITS.hash());
        assertThat(d.oracle().of("SOL").orElseThrow().sources()).containsExactly("jupiter", "pyth");
    }

    @Test
    void executionRequiresAReadingAndRechecksItAgainstTheWorld() {
        Wallet w = Fixtures.wallet(SolanaCluster.DEVNET);
        PolicyEngine engine = engine(defaults());
        ActionProposal p = proposal(w, new BigDecimal("50"), true, true);
        PolicyDecision decided = engine.evaluate(p, w, fresh(), signer(w.address()));
        // approved with an already-elapsed timelock (KAN-438): the oracle is the only thing under test here
        ActionProposal approved = p.withPolicy(decided, NOW).withStatus(ProposalStatus.AWAITING_APPROVAL, NOW)
                .withApproval(new ApprovalRecord(ApprovalRecord.Decision.APPROVED, "op", NOW, null, NOW), NOW).withStatus(ProposalStatus.APPROVED, NOW);
        assertThat(engine.executionPreconditions(approved)).isEmpty();
        // a row decided before the oracle existed does not execute
        ActionProposal blind = approved.withPolicy(new PolicyDecision(true, true, List.of(), List.of(), List.of(), NOW, decided.authorization(), decided.input()), NOW);
        assertThat(engine.executionPreconditions(blind)).singleElement().asString().contains("No oracle reading");
        // the fresh reading at execution time: the same checks, on the world as it is now
        assertThat(engine.priceViolations(approved, Fixtures.oracle("108", NOW))).isEmpty();
        assertThat(engine.priceViolations(approved, Fixtures.oracle("115", NOW))).singleElement()
                .satisfies(v -> assertThat(v.rule()).isEqualTo(PolicyEngine.RULE_PRICE_DRIFT)).asString().contains("1305 bps apart");
        assertThat(engine.priceViolations(approved, null)).singleElement().satisfies(v -> assertThat(v.rule()).isEqualTo(PolicyEngine.RULE_PRICE_QUORUM));
    }

    // ---- KAN-572: every price-integrity refusal names its rule, with a message a human reads -------

    @Test
    void staleSourcesBlockWithPriceStaleAndTheAges() {
        Wallet w = Fixtures.wallet(SolanaCluster.DEVNET);
        // both sources answered SOL $100, but 15 minutes ago: the quorum is lost to staleness (max age 60 s)
        OracleReading stale = new OracleReading(NOW, Fixtures.ORACLE_LIMITS, List.of(
                Fixtures.consensus("SOL", TokenRegistry.NATIVE_SOL_MINT, "100", "100", NOW.minusSeconds(900), NOW),
                Fixtures.consensus("USDC", TokenRegistry.USDC_MINT, "1", "1", NOW)));
        PolicyDecision d = engine(defaults()).evaluate(proposal(w, new BigDecimal("50"), true, true), w, WalletState.fresh(NOW, stale), signer(w.address()));
        assertThat(d.allowed()).isFalse();
        assertThat(d.violations()).extracting(PolicyDecision.Violation::rule).containsExactly(PolicyEngine.RULE_PRICE_STALE, PolicyEngine.RULE_AUTHORIZATION);
        assertThat(d.violations().get(0).message()).contains("SOL: 0 fresh source(s), quorum is 2").contains("older than 60s").contains("observed 900s ago");
    }

    @Test
    void singleSourceBlocksWithPriceQuorum() {
        Wallet w = Fixtures.wallet(SolanaCluster.DEVNET);
        OracleReading one = new OracleReading(NOW, Fixtures.ORACLE_LIMITS, List.of(
                PriceOracle.consensus("SOL", TokenRegistry.NATIVE_SOL_MINT, List.of(new PriceObservation("jupiter", "SOL", TokenRegistry.NATIVE_SOL_MINT, new BigDecimal("100"), NOW)),
                        null, NOW, Fixtures.ORACLE_LIMITS),
                Fixtures.consensus("USDC", TokenRegistry.USDC_MINT, "1", "1", NOW)));
        PolicyDecision d = engine(defaults()).evaluate(proposal(w, new BigDecimal("50"), true, true), w, WalletState.fresh(NOW, one), signer(w.address()));
        assertThat(d.violations()).extracting(PolicyDecision.Violation::rule).containsExactly(PolicyEngine.RULE_PRICE_QUORUM, PolicyEngine.RULE_AUTHORIZATION);
        assertThat(d.violations().get(0).message()).contains("SOL: 1 usable source(s) [jupiter=$100], quorum is 2");
    }

    @Test
    void marketCrashBetweenTwoReadingsTripsTheCircuitBreaker() {
        Wallet w = Fixtures.wallet(SolanaCluster.DEVNET);
        OracleConsensus before = Fixtures.consensus("SOL", TokenRegistry.NATIVE_SOL_MINT, "100", "100", NOW.minusSeconds(30));
        OracleConsensus crashed = PriceOracle.consensus("SOL", TokenRegistry.NATIVE_SOL_MINT, List.of(
                new PriceObservation("jupiter", "SOL", TokenRegistry.NATIVE_SOL_MINT, new BigDecimal("10"), NOW),
                new PriceObservation("pyth", "SOL", TokenRegistry.NATIVE_SOL_MINT, new BigDecimal("10"), NOW)), before, NOW, Fixtures.ORACLE_LIMITS);
        OracleReading reading = new OracleReading(NOW, Fixtures.ORACLE_LIMITS, List.of(crashed, Fixtures.consensus("USDC", TokenRegistry.USDC_MINT, "1", "1", NOW)));
        PolicyDecision d = engine(defaults()).evaluate(proposal(w, new BigDecimal("50"), true, true), w, WalletState.fresh(NOW, reading), signer(w.address()));
        assertThat(d.violations()).extracting(PolicyDecision.Violation::rule).containsExactly(PolicyEngine.RULE_PRICE_CIRCUIT_BREAKER, PolicyEngine.RULE_AUTHORIZATION);
        assertThat(d.violations().get(0).message()).contains("SOL moved 9000 bps (100 → 10)").contains("circuit breaker");
    }

    // ---- KAN-572: the signer's own caps, visible before the signer refuses -------------------------

    @Test
    void signerCapsAreVisibleAsExecutionRules() {
        Wallet w = Fixtures.wallet(SolanaCluster.DEVNET);
        ActionProposal p = proposal(w, new BigDecimal("50"), true, true);
        PolicyDecision ok = engine(defaults()).evaluate(p, w, fresh(), signer(w.address()));
        assertThat(ok.executable()).isTrue();
        assertThat(ok.rulesApplied()).contains(PolicyEngine.RULE_SIGNER_DESTINATION, PolicyEngine.RULE_SIGNER_MAX_LAMPORTS, PolicyEngine.RULE_SIGNER_CLUSTER);
        // the vault is not in the signer's allowlist, the cap is below the transfer, and the signer is pinned to another cluster
        SignerClient.Identity strict = new SignerClient.Identity(w.address(), "mainnet-beta", 1L, List.of("SomeOtherDestination111111111111111111111111"));
        PolicyDecision refused = engine(defaults()).evaluate(p, w, fresh(), Optional.of(strict));
        assertThat(refused.allowed()).isTrue(); // the human still sees it: these are execution rules
        assertThat(refused.executable()).isFalse();
        assertThat(refused.executionViolations()).extracting(PolicyDecision.Violation::rule)
                .contains(PolicyEngine.RULE_SIGNER_DESTINATION, PolicyEngine.RULE_SIGNER_MAX_LAMPORTS, PolicyEngine.RULE_SIGNER_CLUSTER);
        assertThat(refused.executionViolations()).extracting(PolicyDecision.Violation::message)
                .anySatisfy(m -> assertThat(m).contains("Destination " + Fixtures.VAULT + " is not in the signer's allowlist"))
                .anySatisfy(m -> assertThat(m).contains("the signer's cap is 1"))
                .anySatisfy(m -> assertThat(m).contains("The signer only signs for mainnet-beta; this wallet is on devnet"));
        // an unknown cap / empty allowlist is not a refusal (the signer decides on its own bytes anyway)
        PolicyDecision lax = engine(defaults()).evaluate(p, w, fresh(), Optional.of(new SignerClient.Identity(w.address(), null, 0L, null)));
        assertThat(lax.executable()).isTrue();
    }
}
