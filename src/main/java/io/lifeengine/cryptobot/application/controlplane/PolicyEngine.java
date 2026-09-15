package io.lifeengine.cryptobot.application.controlplane;

import io.lifeengine.cryptobot.adapters.solana.Base58;
import io.lifeengine.cryptobot.domain.policy.PolicyDecision;
import io.lifeengine.cryptobot.domain.strategy.RebalanceLeg;
import io.lifeengine.cryptobot.domain.transactions.ActionProposal;
import io.lifeengine.cryptobot.domain.transactions.ProposalStatus;
import io.lifeengine.cryptobot.domain.wallet.Wallet;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Deterministic policy over a fully simulated proposal. It runs <em>after</em> simulation and
 * <em>before</em> the human sees it, so what reaches the approval screen is already inside
 * the limits — and what is outside them is recorded as {@code BLOCKED_BY_POLICY} with the rule.
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

    private final PolicyProperties props;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public PolicyEngine(PolicyProperties props) {
        this(props, Clock.systemUTC());
    }

    PolicyEngine(PolicyProperties props, Clock clock) {
        this.props = props;
        this.clock = clock;
    }

    public PolicyProperties properties() {
        return props;
    }

    /**
     * @param proposal the simulated proposal (plan + simulation + transaction filled in)
     * @param wallet its wallet
     * @param lastExecutedAt when this wallet last executed anything, for the cooldown
     * @param signerPublicKey the signer's identity if the signer is reachable, else empty
     */
    public PolicyDecision evaluate(ActionProposal proposal, Wallet wallet, Optional<Instant> lastExecutedAt, Optional<String> signerPublicKey) {
        List<PolicyDecision.Violation> blocking = new ArrayList<>();
        List<PolicyDecision.Violation> execution = new ArrayList<>();
        List<String> applied = new ArrayList<>();

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
        Instant now = clock.instant();
        if (lastExecutedAt.isPresent() && Duration.between(lastExecutedAt.get(), now).compareTo(props.cooldown()) < 0) {
            blocking.add(new PolicyDecision.Violation(RULE_COOLDOWN, "This wallet executed a trade " + Duration.between(lastExecutedAt.get(), now).toSeconds() + "s ago; cooldown is " + props.cooldown().toSeconds() + "s"));
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

        return new PolicyDecision(blocking.isEmpty(), blocking.isEmpty() && execution.isEmpty(), blocking, execution, applied, now);
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
        if (proposal.approval() == null || proposal.approval().decision() != io.lifeengine.cryptobot.domain.transactions.ApprovalRecord.Decision.APPROVED) {
            problems.add("No approval record");
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
}
