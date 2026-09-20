package io.lifeengine.cryptobot.application.controlplane;

import io.lifeengine.cryptobot.adapters.solana.Base58;
import io.lifeengine.cryptobot.domain.oracle.OracleConsensus;
import io.lifeengine.cryptobot.domain.oracle.OracleReading;
import io.lifeengine.cryptobot.domain.oracle.PriceOracle;
import io.lifeengine.cryptobot.domain.policy.DeterministicPolicyEngine;
import io.lifeengine.cryptobot.domain.policy.PolicyDecision;
import io.lifeengine.cryptobot.domain.policy.PolicyInput;
import io.lifeengine.cryptobot.domain.policy.PolicyPredicate;
import io.lifeengine.cryptobot.domain.policy.PolicyRules;
import io.lifeengine.cryptobot.domain.policy.PolicyVerdict;
import io.lifeengine.cryptobot.domain.strategy.RebalanceLeg;
import io.lifeengine.cryptobot.domain.transactions.ActionProposal;
import io.lifeengine.cryptobot.domain.transactions.ApprovalRecord;
import io.lifeengine.cryptobot.domain.transactions.ProposalStatus;
import io.lifeengine.cryptobot.domain.wallet.Wallet;
import io.lifeengine.cryptobot.integration.validator.ValidatorClient;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Deterministic policy over a fully simulated proposal. It runs <em>after</em> simulation and
 * <em>before</em> the human sees it, so what reaches the approval screen is already inside
 * the limits — and what is outside them is recorded as {@code BLOCKED_BY_POLICY} with the rule.
 *
 * <p>Two layers, one verdict (KAN-436):
 * <ul>
 *   <li>the legacy named rules (kill switch, cooldown, cluster, vault, simulation, signer …) that
 *       decide <em>approvable</em> and <em>executable</em>;
 *   <li>the versioned {@link DeterministicPolicyEngine} over {@code (I, S, R_v)} that decides
 *       ALLOW / ESCALATE / DENY by predicates and tier. Its verdict — with {@code H_R} — goes
 *       into {@link PolicyDecision#authorization()}; DENY is a blocking violation named
 *       {@link #RULE_AUTHORIZATION}.
 * </ul>
 * And before either may trust the state, the oracle rule (KAN-439, {@link #RULE_ORACLE}): the
 * prices in {@code S} come from a multi-source consensus under committed integrity limits, or
 * there is no {@code S} and the answer is DENY. The reading travels in
 * {@link PolicyDecision#oracle()} as the state reference of the decision.
 * Today every proposal still waits for the human, whatever the tier says: ALLOW and
 * REQUIRE_SECOND_AGENT are recorded, not acted on (no autonomous execution). The verdict is what
 * the receipt commits to and what the independent validator re-derives before signing (KAN-438).
 *
 * <p>KAN-438 adds two execution-time facts: the validator must be reachable and hold the same
 * {@code H_R} ({@link #RULE_VALIDATOR}), and the approval's timelock must have elapsed
 * ({@link #RULE_TIMELOCK}, paper §19).
 */
@Service
public class PolicyEngine {

    public static final String RULE_KILL_SWITCH = "EXECUTION_ENABLED";
    public static final String RULE_ASSET_ALLOWLIST = "ASSET_ALLOWLIST";
    public static final String RULE_MAX_TRADE_USD = "MAX_TRADE_USD";
    public static final String RULE_MAX_TRADE_PCT = "MAX_TRADE_PCT_OF_PORTFOLIO";
    public static final String RULE_COOLDOWN = "COOLDOWN";
    public static final String RULE_CLUSTER = "EXECUTION_CLUSTER";
    public static final String RULE_MAX_LAMPORTS = "MAX_LAMPORTS_PER_TX";
    public static final String RULE_VAULT = "REBALANCE_VAULT_CONFIGURED";
    public static final String RULE_SIMULATION = "ONCHAIN_SIMULATION_PASSED";
    public static final String RULE_SIGNER = "SIGNER_CONTROLS_WALLET";
    /** The deterministic verdict said DENY; the message lists the failed predicates. */
    public static final String RULE_AUTHORIZATION = "AUTHORIZATION";
    /** KAN-438: the independent validator is reachable and pinned to the same {@code H_R}. */
    public static final String RULE_VALIDATOR = "VALIDATOR_AVAILABLE";
    /** KAN-438: the approval's timelock has elapsed. */
    public static final String RULE_TIMELOCK = "TIMELOCK_ELAPSED";
    /**
     * KAN-439 (paper §22): every asset the plan touches has a multi-source consensus — quorum,
     * fresh, sources within the deviation bound, breaker not tripped — and the price the plan was
     * built on is within {@code max_move_bps} of that consensus. Otherwise the state is corrupt or
     * unknown, and {@code CorrectRules + CorruptState ⇏ SafeExecution}: DENY.
     */
    public static final String RULE_ORACLE = "ORACLE_INTEGRITY";

    /**
     * What the caller resolved from the authoritative state for this wallet, at evaluation time.
     *
     * @param lastExecutedAt when this wallet last executed anything, for the cooldown
     * @param executedLast24hUsd notional already executed by this wallet in the last 24 h ({@code daily_exposure})
     * @param pricesAsOf when the snapshot the plan was built on was valued (part of {@code oracle_age})
     * @param oracle the fresh multi-source reading for the plan's assets (KAN-439); {@code null} = unknown ⇒ deny
     */
    public record WalletState(Optional<Instant> lastExecutedAt, BigDecimal executedLast24hUsd, Instant pricesAsOf, OracleReading oracle) {
        public WalletState {
            lastExecutedAt = lastExecutedAt == null ? Optional.empty() : lastExecutedAt;
        }

        public static WalletState fresh(Instant pricesAsOf, OracleReading oracle) {
            return new WalletState(Optional.empty(), BigDecimal.ZERO, pricesAsOf, oracle);
        }
    }

    private final PolicyProperties props;
    private final AuthorizationProperties authorization;
    private final TimelockProperties timelock;
    private final PolicyRules rules;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public PolicyEngine(PolicyProperties props, AuthorizationProperties authorization, TimelockProperties timelock) {
        this(props, authorization, timelock, Clock.systemUTC());
    }

    PolicyEngine(PolicyProperties props, AuthorizationProperties authorization, Clock clock) {
        this(props, authorization, new TimelockProperties(null, null, null), clock);
    }

    PolicyEngine(PolicyProperties props, AuthorizationProperties authorization, TimelockProperties timelock, Clock clock) {
        this.props = props;
        this.authorization = authorization;
        this.timelock = timelock == null ? new TimelockProperties(null, null, null) : timelock;
        this.rules = authorization.rules(props); // throws ⇒ the service does not start without a valid policy
        this.clock = clock;
    }

    public PolicyProperties properties() {
        return props;
    }

    public TimelockProperties timelock() {
        return timelock;
    }

    /** {@code R_v} in force in this process. */
    public PolicyRules rules() {
        return rules;
    }

    /**
     * @param proposal the simulated proposal (plan + simulation + transaction filled in)
     * @param wallet its wallet
     * @param state what the caller knows about the wallet right now
     * @param signerPublicKey the signer's identity if the signer is reachable, else empty
     */
    public PolicyDecision evaluate(ActionProposal proposal, Wallet wallet, WalletState state, Optional<String> signerPublicKey) {
        List<PolicyDecision.Violation> blocking = new ArrayList<>();
        List<PolicyDecision.Violation> execution = new ArrayList<>();
        List<String> applied = new ArrayList<>();
        Instant now = clock.instant();
        Optional<Instant> lastExecutedAt = state.lastExecutedAt();

        // --- blocking rules: the proposal is not even shown for approval ---------------------
        applied.add(RULE_ASSET_ALLOWLIST);
        for (RebalanceLeg leg : proposal.plan().legs()) {
            if (!allowed(leg.symbol())) {
                blocking.add(new PolicyDecision.Violation(RULE_ASSET_ALLOWLIST, leg.symbol() + " is not in the allowed asset list " + props.allowedAssets()));
            }
            if (!allowed(leg.counterAsset())) {
                blocking.add(new PolicyDecision.Violation(RULE_ASSET_ALLOWLIST, "Counter asset " + leg.counterAsset() + " is not allowed"));
            }
        }

        applied.add(RULE_MAX_TRADE_USD);
        BigDecimal turnover = proposal.plan().turnoverUsd() == null ? BigDecimal.ZERO : proposal.plan().turnoverUsd();
        if (turnover.compareTo(props.maxTradeUsd()) > 0) {
            blocking.add(new PolicyDecision.Violation(RULE_MAX_TRADE_USD, "Trade notional $" + turnover.setScale(2, RoundingMode.HALF_UP) + " exceeds the $" + props.maxTradeUsd() + " limit"));
        }

        applied.add(RULE_MAX_TRADE_PCT);
        BigDecimal total = proposal.plan().totalUsd();
        if (total != null && total.signum() > 0) {
            BigDecimal pct = turnover.multiply(new BigDecimal("100")).divide(total, 2, RoundingMode.HALF_UP);
            if (pct.compareTo(props.maxTradePctOfPortfolio()) > 0) {
                blocking.add(new PolicyDecision.Violation(RULE_MAX_TRADE_PCT, "Trade moves " + pct + "% of the portfolio; limit is " + props.maxTradePctOfPortfolio() + "%"));
            }
        }

        applied.add(RULE_COOLDOWN);
        if (lastExecutedAt.isPresent() && Duration.between(lastExecutedAt.get(), now).compareTo(props.cooldown()) < 0) {
            blocking.add(new PolicyDecision.Violation(RULE_COOLDOWN, "This wallet executed a trade " + Duration.between(lastExecutedAt.get(), now).toSeconds() + "s ago; cooldown is " + props.cooldown().toSeconds() + "s"));
        }

        // --- KAN-439: the state must be priced by a consensus before any rule may trust it ---
        applied.add(RULE_ORACLE);
        for (String problem : oracleProblems(proposal, state.oracle())) {
            blocking.add(new PolicyDecision.Violation(RULE_ORACLE, problem));
        }

        // --- the deterministic verdict over (I, S, R_v) -------------------------------------
        applied.add(RULE_AUTHORIZATION);
        PolicyInput input = policyInput(proposal, wallet, state, now);
        PolicyVerdict verdict = DeterministicPolicyEngine.evaluate(rules, input);
        if (verdict.denied()) {
            blocking.add(new PolicyDecision.Violation(RULE_AUTHORIZATION, "Policy " + rules.version() + " (" + rules.hash() + ") denied: "
                    + verdict.failedPredicates().stream().map(p -> p.name() + " [" + p.formula() + "]").toList()));
        }

        // --- execution rules: approvable as paper, but must not reach the chain -------------
        applied.add(RULE_KILL_SWITCH);
        if (!props.executionEnabled()) {
            execution.add(new PolicyDecision.Violation(RULE_KILL_SWITCH, "Emergency stop is active (cryptobot.policy.execution-enabled=false)"));
        }

        applied.add(RULE_CLUSTER);
        if (!wallet.cluster().id().equalsIgnoreCase(props.executionCluster())) {
            execution.add(new PolicyDecision.Violation(RULE_CLUSTER, "Execution is only allowed on " + props.executionCluster() + "; this wallet is on " + wallet.cluster().id()));
        }

        applied.add(RULE_VAULT);
        if (props.rebalanceVault().isEmpty() || !Base58.isPublicKey(props.rebalanceVault())) {
            execution.add(new PolicyDecision.Violation(RULE_VAULT, "No valid rebalance vault address configured"));
        } else if (props.rebalanceVault().equals(wallet.address())) {
            execution.add(new PolicyDecision.Violation(RULE_VAULT, "Rebalance vault must differ from the wallet"));
        }

        applied.add(RULE_MAX_LAMPORTS);
        if (proposal.transaction() != null && proposal.transaction().lamports() > props.maxLamportsPerTx()) {
            execution.add(new PolicyDecision.Violation(RULE_MAX_LAMPORTS, "Transaction moves " + proposal.transaction().lamports() + " lamports; cap is " + props.maxLamportsPerTx()));
        }

        applied.add(RULE_SIMULATION);
        if (proposal.transaction() == null) {
            execution.add(new PolicyDecision.Violation(RULE_SIMULATION, "No executable transaction was prepared for this plan (only SOL sell legs can be executed in this version)"));
        } else if (proposal.simulation() == null || !proposal.simulation().passed()) {
            String err = proposal.simulation() == null || proposal.simulation().onchain() == null ? "not simulated" : proposal.simulation().onchain().error();
            execution.add(new PolicyDecision.Violation(RULE_SIMULATION, "On-chain simulation did not pass: " + err));
        }

        applied.add(RULE_SIGNER);
        if (signerPublicKey.isEmpty()) {
            execution.add(new PolicyDecision.Violation(RULE_SIGNER, "Signer service unavailable or disabled"));
        } else if (!signerPublicKey.get().equals(wallet.address())) {
            execution.add(new PolicyDecision.Violation(RULE_SIGNER, "The signer does not control this wallet (read-only wallet): paper trade only"));
        }

        return new PolicyDecision(blocking.isEmpty(), blocking.isEmpty() && execution.isEmpty(), blocking, execution, applied, now, verdict, input, state.oracle());
    }

    /**
     * KAN-438 (paper §20): the proposal is executable only if an independent validator is up and
     * holds exactly the policy this verdict was decided under. Evaluated at proposal time so the
     * human sees "paper trade: validator unavailable" before approving, and again — for real — by
     * the validator itself before anything is signed.
     */
    public PolicyDecision requireValidator(PolicyDecision decision, Optional<ValidatorClient.Identity> validator) {
        if (validator.isEmpty()) {
            return decision.withExecutionViolation(new PolicyDecision.Violation(RULE_VALIDATOR, "Independent validator unavailable or disabled"));
        }
        ValidatorClient.Identity v = validator.get();
        if (!v.enabled()) {
            return decision.withExecutionViolation(new PolicyDecision.Violation(RULE_VALIDATOR, "Independent validator is stopped (VALIDATOR_ENABLED=false)"));
        }
        String ours = decision.authorization() == null ? rules.hash() : decision.authorization().policyHash();
        if (v.policyHash() == null || !v.policyHash().equals(ours)) {
            return decision.withExecutionViolation(new PolicyDecision.Violation(RULE_VALIDATOR,
                    "Validator holds policy " + v.policyHash() + " (" + v.policyVersion() + "); this verdict is under " + ours));
        }
        return decision.withRuleApplied(RULE_VALIDATOR);
    }

    /** Paper §19: when an approval given now becomes executable, by the tier of its verdict. */
    public Instant executableAt(Instant approvedAt, PolicyDecision decision) {
        return approvedAt.plus(timelock.forVerdict(decision == null ? null : decision.authorization()));
    }

    /** The end of the lock for a persisted approval; rows approved before KAN-438 get it derived from {@code at}. */
    public Instant executableAt(ActionProposal proposal) {
        ApprovalRecord a = proposal.approval();
        if (a == null || a.at() == null) {
            return null;
        }
        return a.executableAt() != null ? a.executableAt() : executableAt(a.at(), proposal.policy());
    }

    /**
     * The data-integrity part of the envelope (KAN-439), as a list of problems — empty means the
     * reading may be trusted for this plan. Pure over its arguments, so it is applied twice with
     * the same code: at evaluation (blocking rule {@link #RULE_ORACLE}) and again at execution
     * with a fresh reading (the world may have moved, or the breaker may have tripped).
     *
     * <ul>
     *   <li>no reading ⇒ unknown state ⇒ one problem, nothing else is checked;
     *   <li>every asset the plan touches (each leg's symbol and its counter asset) must have an
     *       accepted consensus in the reading;
     *   <li>the price each leg was built on ({@code estimatedUsd / amount}) must be within
     *       {@code max_move_bps} of the consensus median — the "$18 vs $180" check.
     * </ul>
     */
    public List<String> oracleProblems(ActionProposal proposal, OracleReading reading) {
        List<String> problems = new ArrayList<>();
        if (reading == null) {
            problems.add("No oracle reading: price integrity unknown (fail-closed)");
            return problems;
        }
        List<RebalanceLeg> legs = proposal.plan() == null ? List.of() : proposal.plan().legs();
        List<String> assets = new ArrayList<>();
        for (RebalanceLeg leg : legs) {
            addAsset(assets, leg.symbol());
            addAsset(assets, leg.counterAsset());
        }
        for (String asset : assets) {
            Optional<OracleConsensus> c = reading.of(asset);
            if (c.isEmpty()) {
                problems.add(asset + ": not in the oracle reading");
            } else if (!c.get().accepted()) {
                problems.add(asset + ": no consensus " + c.get().refusals() + (c.get().problems().isEmpty() ? "" : " — " + String.join("; ", c.get().problems())));
            }
        }
        for (RebalanceLeg leg : legs) {
            Optional<OracleConsensus> c = reading.of(leg.symbol());
            if (c.isEmpty() || !c.get().accepted() || leg.amount() == null || leg.amount().signum() <= 0 || leg.estimatedUsd() == null) {
                continue;
            }
            BigDecimal planned = leg.estimatedUsd().divide(leg.amount(), 12, RoundingMode.HALF_UP);
            int drift = PriceOracle.deviationBps(planned, c.get().priceUsd());
            if (drift > reading.limits().maxMoveBps()) {
                problems.add("Plan priced " + leg.symbol() + " at $" + planned.setScale(4, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
                        + " but the oracle median is $" + c.get().priceUsd().setScale(4, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
                        + " (" + drift + " bps apart, limit " + reading.limits().maxMoveBps() + "): re-create the proposal on a fresh snapshot");
            }
        }
        return problems;
    }

    private static void addAsset(List<String> assets, String symbol) {
        if (symbol == null || symbol.isBlank()) {
            return;
        }
        String s = symbol.trim().toUpperCase(Locale.ROOT);
        if (!assets.contains(s)) {
            assets.add(s);
        }
    }

    /**
     * {@code (I, S)} from a rebalance proposal. Nothing is defaulted: a fact the proposal cannot
     * provide stays {@code null} and fails its predicate.
     *
     * <ul>
     *   <li>asset / exposure after: the BUY leg that ends with the largest weight (a buy is what
     *       creates concentration); with no BUY leg, the largest SELL leg and the weight it ends
     *       at. Every leg's asset is still checked by {@link #RULE_ASSET_ALLOWLIST}.
     *   <li>trade value: the whole plan's turnover, cents rounded up.
     *   <li>slippage: the tolerance the executor applies ({@code executor-slippage-bps}); the
     *       intent does not carry one yet (KAN-435 producer).
     *   <li>oracle age: seconds since the oldest price fact used — the snapshot the plan was priced
 *       on or the oldest observation behind the fresh consensus, whichever is older (KAN-439).
     *   <li>expiry: epoch seconds on both sides until intents carry a Solana slot.
     *   <li>nonce unused: this proposal has never started executing.
     *   <li>agent permitted: the proposal's owner is the wallet's owner.
     * </ul>
     */
    PolicyInput policyInput(ActionProposal proposal, Wallet wallet, WalletState state, Instant now) {
        List<RebalanceLeg> legs = proposal.plan().legs();
        Optional<RebalanceLeg> headline = legs.stream()
                .filter(l -> l.action() == RebalanceLeg.Action.BUY)
                .max(Comparator.comparing(RebalanceLeg::weightPctAfter, Comparator.nullsFirst(Comparator.naturalOrder())));
        if (headline.isEmpty()) {
            headline = legs.stream().max(Comparator.comparing(RebalanceLeg::estimatedUsd, Comparator.nullsFirst(Comparator.naturalOrder())));
        }
        String asset = headline.map(RebalanceLeg::symbol).orElse(null);
        Integer exposureAfterBps = headline.map(RebalanceLeg::weightPctAfter).map(PolicyEngine::bps).orElse(null);
        BigDecimal turnover = proposal.plan().turnoverUsd();
        Long tradeValueCents = turnover == null || turnover.signum() < 0 ? null : AuthorizationProperties.tradeCents(turnover);
        Long validUntil = proposal.expiresAt() == null ? null : proposal.expiresAt().getEpochSecond();

        PolicyInput.IntentFacts intent = new PolicyInput.IntentFacts(
                proposal.requestedBy(),
                proposal.kind(),
                rules.version(),
                asset,
                tradeValueCents,
                authorization.executorSlippageBps(),
                validUntil);

        BigDecimal executed = state.executedLast24hUsd();
        Long dailyExposureCents = executed == null || executed.signum() < 0 ? null : AuthorizationProperties.tradeCents(executed);
        Long oracleAge = oracleAgeSeconds(state, now);
        boolean agentPermitted = proposal.ownerUserId() != null && proposal.ownerUserId().equals(wallet.ownerUserId());
        boolean nonceUnused = proposal.operationId() == null && proposal.execution() == null && !proposal.status().inFlight()
                && proposal.status() != ProposalStatus.EXECUTED;

        PolicyInput.StateFacts facts = new PolicyInput.StateFacts(
                dailyExposureCents,
                exposureAfterBps,
                oracleAge,
                agentPermitted,
                nonceUnused,
                now.getEpochSecond());
        return new PolicyInput(intent, facts);
    }

    /**
     * {@code oracle_age}: the age of the oldest price fact the decision depends on — the snapshot
     * the plan was built on <em>and</em> the oldest observation behind the fresh consensus
     * (KAN-439). Either unknown, or an unaccepted reading ⇒ {@code null} ⇒ {@code ORACLE_FRESH} fails.
     */
    static Long oracleAgeSeconds(WalletState state, Instant now) {
        if (state.pricesAsOf() == null || state.pricesAsOf().isAfter(now) || state.oracle() == null) {
            return null;
        }
        Optional<Instant> consensusAsOf = state.oracle().asOf();
        if (consensusAsOf.isEmpty()) {
            return null;
        }
        Instant oldest = consensusAsOf.get().isBefore(state.pricesAsOf()) ? consensusAsOf.get() : state.pricesAsOf();
        return oldest.isAfter(now) ? 0L : Duration.between(oldest, now).getSeconds();
    }

    /** Re-checked at execution time — the world may have changed since approval. */
    public List<String> executionPreconditions(ActionProposal proposal) {
        List<String> problems = new ArrayList<>();
        if (proposal.status() != ProposalStatus.APPROVED) {
            problems.add("Proposal is " + proposal.status() + ", not APPROVED");
        }
        if (!props.executionEnabled()) {
            problems.add("Emergency stop is active");
        }
        if (proposal.policy() == null || !proposal.policy().executable()) {
            problems.add("Policy marked this proposal as not executable");
        }
        PolicyVerdict verdict = proposal.policy() == null ? null : proposal.policy().authorization();
        if (verdict == null) {
            problems.add("No policy verdict on this proposal (evaluated before KAN-436): re-create it");
        } else if (verdict.denied()) {
            problems.add("Policy verdict is DENY: " + verdict.failedPredicates());
        } else if (!rules.hash().equals(verdict.policyHash())) {
            // Policy-binding invariant (paper §23): what was approved under R_v does not execute under R_w.
            problems.add("Policy changed since evaluation (" + verdict.policyHash() + " → " + rules.hash() + "): re-create the proposal");
        } else if (proposal.policy().input() == null) {
            problems.add("No recorded (I, S) on this proposal (evaluated before KAN-438): the validator cannot re-derive it; re-create it");
        }
        if (proposal.policy() != null && proposal.policy().oracle() == null) {
            problems.add("No oracle reading on this proposal (evaluated before KAN-439): re-create it");
        }
        if (proposal.approval() == null || proposal.approval().decision() != ApprovalRecord.Decision.APPROVED) {
            problems.add("No approval record");
        } else {
            Instant executableAt = executableAt(proposal);
            Instant now = clock.instant();
            if (executableAt != null && now.isBefore(executableAt)) {
                problems.add("Timelock: executable at " + executableAt + " (" + Duration.between(now, executableAt).toSeconds()
                        + "s remaining); cancel it or wait");
            }
        }
        if (proposal.expiresAt() != null && clock.instant().isAfter(proposal.expiresAt())) {
            problems.add("Proposal expired at " + proposal.expiresAt());
        }
        return problems;
    }

    private boolean allowed(String symbol) {
        String s = symbol == null ? "" : symbol.toUpperCase(Locale.ROOT);
        return props.allowedAssets().stream().anyMatch(a -> a.equalsIgnoreCase(s));
    }

    /** Percent (0–100, any scale) → basis points, rounded up; outside 0..100 → {@code null} (unknown). */
    static Integer bps(BigDecimal pct) {
        if (pct == null) {
            return null;
        }
        BigDecimal b = pct.movePointRight(2).setScale(0, RoundingMode.CEILING);
        if (b.signum() < 0 || b.compareTo(BigDecimal.valueOf(PolicyRules.MAX_BPS)) > 0) {
            return null;
        }
        return b.intValueExact();
    }
}
