package io.lifeengine.cryptobot.application.controlplane;

import io.lifeengine.cryptobot.solana.rpc.Base58;
import io.lifeengine.cryptobot.core.oracle.OracleConsensus;
import io.lifeengine.cryptobot.core.oracle.OracleLimits;
import io.lifeengine.cryptobot.core.oracle.OracleReading;
import io.lifeengine.cryptobot.core.oracle.OracleRefusal;
import io.lifeengine.cryptobot.core.oracle.PriceOracle;
import io.lifeengine.cryptobot.core.policy.DeterministicPolicyEngine;
import io.lifeengine.cryptobot.core.policy.PolicyDecision;
import io.lifeengine.cryptobot.core.policy.PolicyInput;
import io.lifeengine.cryptobot.core.policy.PolicyPredicate;
import io.lifeengine.cryptobot.core.policy.PolicyRules;
import io.lifeengine.cryptobot.core.policy.PolicyVerdict;
import io.lifeengine.cryptobot.trading.strategy.RebalanceLeg;
import io.lifeengine.cryptobot.core.execution.ActionProposal;
import io.lifeengine.cryptobot.core.execution.ApprovalRecord;
import io.lifeengine.cryptobot.core.execution.ProposalStatus;
import io.lifeengine.cryptobot.core.wallet.Wallet;
import io.lifeengine.cryptobot.integration.signer.SignerClient;
import io.lifeengine.cryptobot.integration.validator.ValidatorClient;
import io.lifeengine.cryptobot.observability.CryptobotMetrics;
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
 * <p>Two layers, one verdict:
 * <ul>
 *   <li>the legacy named rules (kill switch, cooldown, cluster, vault, simulation, signer …) that
 *       decide <em>approvable</em> and <em>executable</em>;
 *   <li>the versioned {@link DeterministicPolicyEngine} over {@code (I, S, R_v)} that decides
 *       ALLOW / ESCALATE / DENY by predicates and tier. Its verdict — with {@code H_R} — goes
 *       into {@link PolicyDecision#authorization()}; DENY is a blocking violation named
 *       {@link #RULE_AUTHORIZATION}.
 * </ul>
 * And before either may trust the state, the price-integrity rules (
 * {@link #PRICE_RULES}): the prices in {@code S} come from a multi-source consensus under
 * committed integrity limits — quorum, freshness, deviation between sources, circuit breaker — and
 * the price the plan was built on agrees with that consensus; otherwise there is no {@code S} and
 * the answer is a blocking violation named after the check that failed ({@code PRICE_DEVIATION},
 * {@code PRICE_STALE}, …), with a message a human can read. The reading travels in
 * {@link PolicyDecision#oracle()} as the state reference of the decision.
 * Today every proposal still waits for the human, whatever the tier says: ALLOW and
 * REQUIRE_SECOND_AGENT are recorded, not acted on (no autonomous execution). The verdict is what
 * the receipt commits to and what the independent validator re-derives before signing.
 *
 * <p>an internal ticket adds two execution-time facts: the validator must be reachable and hold the same
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
    /** the independent validator is reachable and pinned to the same {@code H_R}. */
    public static final String RULE_VALIDATOR = "VALIDATOR_AVAILABLE";
    /** the approval's timelock has elapsed. */
    public static final String RULE_TIMELOCK = "TIMELOCK_ELAPSED";
    /**
     * (paper §22): the price-integrity rules, one per check, so a blocked intent
     * says <em>which</em> assumption the state violated. Every asset the plan touches must have a
     * multi-source consensus and the price the plan was built on must agree with it. Otherwise the
     * state is corrupt or unknown, and {@code CorrectRules + CorruptState ⇏ SafeExecution}: blocked.
     * <ul>
     *   <li>{@link #RULE_PRICE_QUORUM} — at least {@code min_sources} independent, valid, distinct
     *       sources answered (no reading at all, an unknown asset, a single source: not a fact);
     *   <li>{@link #RULE_PRICE_STALE} — the quorum was lost to observations older than {@code max_age};
     *   <li>{@link #RULE_PRICE_DEVIATION} — every source used is within {@code max_deviation_bps}
     *       of the median (one source saying $18 while the others say $180 is refused, not averaged);
     *   <li>{@link #RULE_PRICE_CIRCUIT_BREAKER} — the median did not move more than {@code max_move_bps}
     *       against the last accepted consensus inside {@code move_interval};
     *   <li>{@link #RULE_PRICE_DRIFT} — the price each leg was planned at is within {@code max_move_bps}
     *       of the fresh median (the "$18 vs $180" check between the snapshot and the world now).
     * </ul>
     */
    public static final String RULE_PRICE_QUORUM = "PRICE_QUORUM";
    public static final String RULE_PRICE_STALE = "PRICE_STALE";
    public static final String RULE_PRICE_DEVIATION = "PRICE_DEVIATION";
    public static final String RULE_PRICE_CIRCUIT_BREAKER = "PRICE_CIRCUIT_BREAKER";
    public static final String RULE_PRICE_DRIFT = "PRICE_DRIFT";
    /** The price-integrity rules, in the order they are applied. */
    public static final List<String> PRICE_RULES = List.of(RULE_PRICE_QUORUM, RULE_PRICE_STALE, RULE_PRICE_DEVIATION, RULE_PRICE_CIRCUIT_BREAKER, RULE_PRICE_DRIFT);
    /**
     * the signer's own hard caps, checked here so the human (and the receipt) see them
     * before the signer would refuse: the destination of the prepared transaction is in the
     * signer's allowlist, its lamports are under the signer's cap, and the signer is pinned to the
     * wallet's cluster. The signer re-checks all three on its own bytes; these rules make the
     * refusal visible one step earlier.
     */
    public static final String RULE_SIGNER_DESTINATION = "SIGNER_DESTINATION_ALLOWLISTED";
    public static final String RULE_SIGNER_MAX_LAMPORTS = "SIGNER_MAX_LAMPORTS";
    public static final String RULE_SIGNER_CLUSTER = "SIGNER_CLUSTER";

    /**
     * What the caller resolved from the authoritative state for this wallet, at evaluation time.
     *
     * @param lastExecutedAt when this wallet last executed anything, for the cooldown
     * @param executedLast24hUsd notional already executed by this wallet in the last 24 h ({@code daily_exposure})
     * @param pricesAsOf when the snapshot the plan was built on was valued (part of {@code oracle_age})
     * @param oracle the fresh multi-source reading for the plan's assets; {@code null} = unknown ⇒ deny
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
     * @param signer the signer's identity (public key, cluster, caps, allowlist) if the signer is reachable, else empty
     */
    public PolicyDecision evaluate(ActionProposal proposal, Wallet wallet, WalletState state, Optional<SignerClient.Identity> signer) {
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

        // --- an internal ticket: the state must be priced by a consensus before any rule may trust it ---
        applied.addAll(PRICE_RULES);
        blocking.addAll(priceViolations(proposal, state.oracle()));

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
        if (signer.isEmpty()) {
            execution.add(new PolicyDecision.Violation(RULE_SIGNER, "Signer service unavailable or disabled"));
        } else if (signer.get().publicKey() == null || !signer.get().publicKey().equals(wallet.address())) {
            execution.add(new PolicyDecision.Violation(RULE_SIGNER, "The signer does not control this wallet (read-only wallet): paper trade only"));
        }

        // --- an internal ticket: the signer's hard caps, visible before the signer would refuse ------------
        applied.add(RULE_SIGNER_DESTINATION);
        applied.add(RULE_SIGNER_MAX_LAMPORTS);
        applied.add(RULE_SIGNER_CLUSTER);
        if (signer.isPresent()) {
            SignerClient.Identity id = signer.get();
            List<String> allowlist = id.allowedDestinations() == null ? List.of() : id.allowedDestinations();
            String destination = props.rebalanceVault();
            if (!allowlist.isEmpty() && destination != null && !destination.isBlank() && !allowlist.contains(destination)) {
                execution.add(new PolicyDecision.Violation(RULE_SIGNER_DESTINATION,
                        "Destination " + destination + " is not in the signer's allowlist " + allowlist + ": the signer would refuse these bytes"));
            }
            if (proposal.transaction() != null && id.maxLamports() > 0 && proposal.transaction().lamports() > id.maxLamports()) {
                execution.add(new PolicyDecision.Violation(RULE_SIGNER_MAX_LAMPORTS,
                        "Transaction moves " + proposal.transaction().lamports() + " lamports; the signer's cap is " + id.maxLamports()));
            }
            if (id.cluster() != null && !id.cluster().isBlank() && !id.cluster().equalsIgnoreCase(wallet.cluster().id())) {
                execution.add(new PolicyDecision.Violation(RULE_SIGNER_CLUSTER,
                        "The signer only signs for " + id.cluster() + "; this wallet is on " + wallet.cluster().id()));
            }
        }

        return new PolicyDecision(blocking.isEmpty(), blocking.isEmpty() && execution.isEmpty(), blocking, execution, applied, now, verdict, input, state.oracle());
    }

    /**
     * (paper §20): the proposal is executable only if an independent validator is up and
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

    /** The end of the lock for a persisted approval; rows approved before a later change get it derived from {@code at}. */
    public Instant executableAt(ActionProposal proposal) {
        ApprovalRecord a = proposal.approval();
        if (a == null || a.at() == null) {
            return null;
        }
        return a.executableAt() != null ? a.executableAt() : executableAt(a.at(), proposal.policy());
    }

    /**
     * The data-integrity part of the envelope as blocking violations, each
     * named after the check that failed and worded for a human — empty means the reading may be
     * trusted for this plan. Pure over its arguments, so it is applied twice with the same code:
     * at evaluation (blocking rules {@link #PRICE_RULES}) and again at execution with a fresh
     * reading (the world may have moved, or the breaker may have tripped).
     *
     * <ul>
     *   <li>no reading ⇒ unknown state ⇒ {@link #RULE_PRICE_QUORUM}, nothing else is checked;
     *   <li>every asset the plan touches (each leg's symbol and its counter asset) must have an
     *       accepted consensus in the reading — each refusal maps to its rule;
     *   <li>the price each leg was built on ({@code estimatedUsd / amount}) must be within
     *       {@code max_move_bps} of the consensus median — {@link #RULE_PRICE_DRIFT}.
     * </ul>
     */
    public List<PolicyDecision.Violation> priceViolations(ActionProposal proposal, OracleReading reading) {
        List<PolicyDecision.Violation> out = new ArrayList<>();
        if (reading == null) {
            out.add(new PolicyDecision.Violation(RULE_PRICE_QUORUM, "No oracle reading: price integrity unknown (fail-closed)"));
            return out;
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
                out.add(new PolicyDecision.Violation(RULE_PRICE_QUORUM, asset + ": not in the oracle reading"));
            } else if (!c.get().accepted()) {
                out.addAll(refusals(c.get(), reading.limits(), reading.readAt()));
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
                out.add(new PolicyDecision.Violation(RULE_PRICE_DRIFT, "Plan priced " + leg.symbol() + " at $" + usd(planned)
                        + " but the oracle median is $" + usd(c.get().priceUsd()) + " (" + drift + " bps apart, limit " + reading.limits().maxMoveBps()
                        + "): re-create the proposal on a fresh snapshot"));
            }
        }
        return out;
    }

    /** One violation per refusal of a consensus, named by what failed; the sources and numbers are in the message. */
    static List<PolicyDecision.Violation> refusals(OracleConsensus c, OracleLimits limits, Instant readAt) {
        List<PolicyDecision.Violation> out = new ArrayList<>();
        String asset = c.asset();
        String sources = c.used().isEmpty() ? "no source" : c.used().stream().map(o -> o.source() + "=$" + usd(o.priceUsd())).toList().toString();
        for (OracleRefusal r : c.refusals()) {
            switch (r) {
                case NO_OBSERVATIONS -> out.add(new PolicyDecision.Violation(RULE_PRICE_QUORUM,
                        asset + ": no price source answered (quorum is " + limits.minSources() + ")"));
                case INSUFFICIENT_SOURCES -> {
                    List<OracleConsensus.Rejected> stale = c.rejected().stream().filter(x -> "STALE".equals(x.reason())).toList();
                    if (!stale.isEmpty()) {
                        String ages = stale.stream().map(x -> x.observation().source() + "=$" + usd(x.observation().priceUsd()) + " observed "
                                + Duration.between(x.observation().observedAt(), readAt).getSeconds() + "s ago").toList().toString();
                        out.add(new PolicyDecision.Violation(RULE_PRICE_STALE, asset + ": " + c.used().size() + " fresh source(s), quorum is " + limits.minSources()
                                + "; stale (older than " + limits.maxAgeSeconds() + "s): " + ages + "; fresh: " + sources));
                    } else {
                        List<String> rejected = c.rejected().stream().map(x -> x.observation().source() + "=" + x.reason()).toList();
                        out.add(new PolicyDecision.Violation(RULE_PRICE_QUORUM, asset + ": " + c.used().size() + " usable source(s) " + sources
                                + ", quorum is " + limits.minSources() + (rejected.isEmpty() ? "" : "; rejected: " + rejected)));
                    }
                }
                case DEVIATION_EXCEEDED -> out.add(new PolicyDecision.Violation(RULE_PRICE_DEVIATION, asset + ": sources disagree — " + sources
                        + ", median $" + usd(c.priceUsd()) + ", limit " + limits.maxDeviationBps() + " bps: "
                        + String.join("; ", c.problems().stream().filter(p -> p.contains("bps apart")).toList())));
                case CIRCUIT_BREAKER -> out.add(new PolicyDecision.Violation(RULE_PRICE_CIRCUIT_BREAKER,
                        String.join("; ", c.problems().stream().filter(p -> p.contains("moved")).toList())
                                + " — circuit breaker: no trade until the move settles or the interval passes"));
            }
        }
        if (out.isEmpty()) {
            // Not accepted without a refusal (no median, no timestamp): still not a fact.
            out.add(new PolicyDecision.Violation(RULE_PRICE_QUORUM, asset + ": no consensus " + c.problems()));
        }
        return out;
    }

    private static String usd(BigDecimal v) {
        return v == null ? "?" : v.setScale(4, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
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
     *       intent does not carry one yet (internal ticket producer).
     *   <li>oracle age: seconds since the oldest price fact used — the snapshot the plan was priced
 *       on or the oldest observation behind the fresh consensus, whichever is older.
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
     *. Either unknown, or an unaccepted reading ⇒ {@code null} ⇒ {@code ORACLE_FRESH} fails.
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

    /**
     * one failed execution precondition with the bounded reason the 409 is counted under
     * ({@code cryptobot_execution_refused_total{reason}}). The message is what the caller reads.
     */
    public record Refusal(CryptobotMetrics.RefusalReason reason, String message) {}

    /** Re-checked at execution time — the world may have changed since approval. Messages only; see {@link #executionRefusals}. */
    public List<String> executionPreconditions(ActionProposal proposal) {
        return executionRefusals(proposal).stream().map(Refusal::message).toList();
    }

    /**
     * Re-checked at execution time, with the reason of each failure. Empty ⇒ the proposal may go
     * to the chain. The most specific reason is the one the metric counts:
     * {@link #primaryRefusal(List)}.
     */
    public List<Refusal> executionRefusals(ActionProposal proposal) {
        List<Refusal> problems = new ArrayList<>();
        if (proposal.status() != ProposalStatus.APPROVED) {
            problems.add(new Refusal(CryptobotMetrics.RefusalReason.STATE, "Proposal is " + proposal.status() + ", not APPROVED"));
        }
        if (!props.executionEnabled()) {
            problems.add(new Refusal(CryptobotMetrics.RefusalReason.POLICY, "Emergency stop is active"));
        }
        if (proposal.policy() == null || !proposal.policy().executable()) {
            // A proposal the cooldown rule blocked is "policy" to the caller; to the dashboard it is
            // the cooldown, which is the one label the issue asks for by name.
            boolean cooldown = proposal.policy() != null && proposal.policy().violations() != null
                    && proposal.policy().violations().stream().anyMatch(v -> RULE_COOLDOWN.equals(v.rule()));
            problems.add(new Refusal(cooldown ? CryptobotMetrics.RefusalReason.COOLDOWN : CryptobotMetrics.RefusalReason.POLICY,
                    "Policy marked this proposal as not executable"));
        }
        PolicyVerdict verdict = proposal.policy() == null ? null : proposal.policy().authorization();
        if (verdict == null) {
            problems.add(new Refusal(CryptobotMetrics.RefusalReason.POLICY, "No policy verdict on this proposal (evaluated before KAN-436): re-create it"));
        } else if (verdict.denied()) {
            problems.add(new Refusal(CryptobotMetrics.RefusalReason.POLICY, "Policy verdict is DENY: " + verdict.failedPredicates()));
        } else if (!rules.hash().equals(verdict.policyHash())) {
            // Policy-binding invariant (paper §23): what was approved under R_v does not execute under R_w.
            problems.add(new Refusal(CryptobotMetrics.RefusalReason.POLICY,
                    "Policy changed since evaluation (" + verdict.policyHash() + " → " + rules.hash() + "): re-create the proposal"));
        } else if (proposal.policy().input() == null) {
            problems.add(new Refusal(CryptobotMetrics.RefusalReason.POLICY,
                    "No recorded (I, S) on this proposal (evaluated before KAN-438): the validator cannot re-derive it; re-create it"));
        }
        if (proposal.policy() != null && proposal.policy().oracle() == null) {
            problems.add(new Refusal(CryptobotMetrics.RefusalReason.POLICY, "No oracle reading on this proposal (evaluated before KAN-439): re-create it"));
        }
        if (proposal.approval() == null || proposal.approval().decision() != ApprovalRecord.Decision.APPROVED) {
            problems.add(new Refusal(CryptobotMetrics.RefusalReason.STATE, "No approval record"));
        } else {
            Instant executableAt = executableAt(proposal);
            Instant now = clock.instant();
            if (executableAt != null && now.isBefore(executableAt)) {
                problems.add(new Refusal(CryptobotMetrics.RefusalReason.TIMELOCK, "Timelock: executable at " + executableAt + " ("
                        + Duration.between(now, executableAt).toSeconds() + "s remaining); cancel it or wait"));
            }
        }
        if (proposal.expiresAt() != null && clock.instant().isAfter(proposal.expiresAt())) {
            problems.add(new Refusal(CryptobotMetrics.RefusalReason.STATE, "Proposal expired at " + proposal.expiresAt()));
        }
        return problems;
    }

    /** The reason the metric counts when several preconditions failed: the first by {@link CryptobotMetrics.RefusalReason} order. */
    public static CryptobotMetrics.RefusalReason primaryRefusal(List<Refusal> refusals) {
        return refusals.stream().map(Refusal::reason).min(Comparator.naturalOrder()).orElse(CryptobotMetrics.RefusalReason.STATE);
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
