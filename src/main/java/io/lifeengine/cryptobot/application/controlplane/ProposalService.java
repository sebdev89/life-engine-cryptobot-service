package io.lifeengine.cryptobot.application.controlplane;

import io.lifeengine.cryptobot.domain.policy.PolicyDecision;
import io.lifeengine.cryptobot.domain.risk.RiskReport;
import io.lifeengine.cryptobot.domain.strategy.RebalanceIntent;
import io.lifeengine.cryptobot.domain.strategy.RebalanceLeg;
import io.lifeengine.cryptobot.domain.strategy.RebalancePlan;
import io.lifeengine.cryptobot.domain.transactions.ActionProposal;
import io.lifeengine.cryptobot.domain.transactions.ApprovalRecord;
import io.lifeengine.cryptobot.domain.transactions.ProposalStatus;
import io.lifeengine.cryptobot.domain.wallet.Wallet;
import io.lifeengine.cryptobot.infrastructure.persistence.controlplane.ActionProposalRepository;
import io.lifeengine.cryptobot.integration.signer.SignerClient;
import io.lifeengine.cryptobot.observability.CryptobotMetrics;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * The pipeline the product is about: plan → risk → simulate → policy → wait for a human.
 * Each step is a persisted transition and an audit event. Nothing here can sign or send.
 */
@Service
public class ProposalService {

    private static final Logger log = LoggerFactory.getLogger(ProposalService.class);

    public static final String EV_CREATED = "PROPOSAL_CREATED";
    public static final String EV_SIMULATED = "SIMULATED";
    public static final String EV_POLICY = "POLICY_EVALUATED";
    public static final String EV_AWAITING = "AWAITING_APPROVAL";
    public static final String EV_BLOCKED = "BLOCKED_BY_POLICY";
    public static final String EV_APPROVED = "APPROVED";
    public static final String EV_REJECTED = "REJECTED";
    public static final String EV_EXPIRED = "EXPIRED";

    private final ActionProposalRepository proposals;
    private final PortfolioService portfolio;
    private final RebalancePlanner planner;
    private final RiskEngine riskEngine;
    private final SimulationService simulation;
    private final PolicyEngine policy;
    private final SignerClient signer;
    private final AuditService audit;
    private final CryptobotMetrics metrics;
    private final Clock clock;

    public ProposalService(
            ActionProposalRepository proposals,
            PortfolioService portfolio,
            RebalancePlanner planner,
            RiskEngine riskEngine,
            SimulationService simulation,
            PolicyEngine policy,
            SignerClient signer,
            AuditService audit,
            CryptobotMetrics metrics) {
        this.proposals = proposals;
        this.portfolio = portfolio;
        this.planner = planner;
        this.riskEngine = riskEngine;
        this.simulation = simulation;
        this.policy = policy;
        this.signer = signer;
        this.audit = audit;
        this.metrics = metrics;
        this.clock = Clock.systemUTC();
    }

    public Mono<ActionProposal> createRebalance(Wallet wallet, String actor, RebalanceIntent intent, String reasoningSummary, UUID runtimeRunId) {
        return portfolio.latest(wallet).flatMap(view -> {
            RebalancePlan plan = planner.plan(view.snapshot(), intent);
            if (plan.isNoop()) {
                metrics.strategyCreated("noop", null);
                return Mono.error(new ControlPlaneExceptions.InvalidRequest("NOOP", "Portfolio is already within the requested targets"));
            }
            metrics.strategyCreated("proposed", assetOf(plan));
            RiskReport riskAfter = riskEngine.evaluate(planner.project(view.snapshot(), plan), null);
            Instant now = clock.instant();
            String title = "Rebalance: " + String.join(", ", intent.targetWeights().keySet()) + " → " + intent.targetWeights().values();
            ActionProposal p = new ActionProposal(UUID.randomUUID(), wallet.id(), wallet.ownerUserId(), wallet.address(), wallet.cluster().id(),
                    ProposalStatus.PROPOSED, "REBALANCE", title, blankToNull(reasoningSummary), actor, intent, plan, view.risk(), riskAfter,
                    null, null, null, null, null, runtimeRunId, view.snapshot().id(), now.plus(policy.properties().proposalTtl()), now, now);
            return proposals.insert(p)
                    .flatMap(saved -> audit.record(saved.ownerUserId(), saved.walletId(), saved.id(), EV_CREATED, actor,
                            payload("plan", plan.summary(), "turnoverUsd", plan.turnoverUsd(), "riskBefore", view.risk().overall(), "riskAfter", riskAfter.overall(),
                                    "runtimeRunId", runtimeRunId)).thenReturn(saved))
                    .flatMap(saved -> simulate(saved, wallet, view.snapshot()))
                    .flatMap(sim -> evaluatePolicy(sim, wallet));
        });
    }

    private Mono<ActionProposal> simulate(ActionProposal p, Wallet wallet, io.lifeengine.cryptobot.domain.portfolio.PortfolioSnapshot snapshot) {
        return simulation.simulate(wallet, snapshot, p.plan())
                .flatMap(sim -> {
                    Instant now = clock.instant();
                    ActionProposal next = p.withSimulation(sim.outcome(), sim.transaction(), now).withStatus(ProposalStatus.SIMULATED, now);
                    return proposals.update(next)
                            .flatMap(saved -> audit.record(saved.ownerUserId(), saved.walletId(), saved.id(), EV_SIMULATED, "cryptobot-service",
                                    payload("onchainOk", sim.outcome().onchain().ok(), "onchainError", sim.outcome().onchain().error(),
                                            "unitsConsumed", sim.outcome().onchain().unitsConsumed(),
                                            "expectedOut", sim.outcome().economic() == null ? null : sim.outcome().economic().expectedBuyAmount(),
                                            "lamports", sim.transaction() == null ? null : sim.transaction().lamports())).thenReturn(saved));
                });
    }

    private Mono<ActionProposal> evaluatePolicy(ActionProposal p, Wallet wallet) {
        Mono<Optional<Instant>> lastExecuted = proposals.findByWallet(wallet.id(), 20)
                .filter(x -> x.status() == ProposalStatus.EXECUTED && x.execution() != null && x.execution().submittedAt() != null)
                .map(x -> x.execution().submittedAt())
                .collectList()
                .map(list -> list.stream().max(Instant::compareTo));
        return Mono.zip(lastExecuted, signer.identity()).flatMap(t -> {
            PolicyDecision decision = policy.evaluate(p, wallet, t.getT1(), t.getT2().map(SignerClient.Identity::publicKey));
            Instant now = clock.instant();
            ProposalStatus next = decision.allowed() ? ProposalStatus.AWAITING_APPROVAL : ProposalStatus.BLOCKED_BY_POLICY;
            ActionProposal updated = p.withPolicy(decision, now).withStatus(next, now);
            log.info("proposal_policy proposalId={} allowed={} executable={} violations={} executionViolations={}",
                    p.id(), decision.allowed(), decision.executable(), decision.violations().size(), decision.executionViolations().size());
            // Funnel step 1: the trade was requested — it either reached the human or policy stopped it.
            metrics.tradeRequested(next.name(), assetOf(p.plan()));
            return proposals.update(updated)
                    .flatMap(saved -> audit.record(saved.ownerUserId(), saved.walletId(), saved.id(), EV_POLICY, "cryptobot-service",
                            payload("allowed", decision.allowed(), "executable", decision.executable(),
                                    "violations", decision.violations().stream().map(v -> v.rule() + ": " + v.message()).toList(),
                                    "executionViolations", decision.executionViolations().stream().map(v -> v.rule() + ": " + v.message()).toList(),
                                    "rulesApplied", decision.rulesApplied())).thenReturn(saved))
                    .flatMap(saved -> audit.record(saved.ownerUserId(), saved.walletId(), saved.id(), decision.allowed() ? EV_AWAITING : EV_BLOCKED,
                            "cryptobot-service", payload("expiresAt", saved.expiresAt())).thenReturn(saved));
        });
    }

    public Mono<ActionProposal> approve(UUID ownerUserId, UUID proposalId, String actor, String note) {
        return decide(ownerUserId, proposalId, actor, note, ApprovalRecord.Decision.APPROVED);
    }

    public Mono<ActionProposal> reject(UUID ownerUserId, UUID proposalId, String actor, String note) {
        return decide(ownerUserId, proposalId, actor, note, ApprovalRecord.Decision.REJECTED);
    }

    private Mono<ActionProposal> decide(UUID ownerUserId, UUID proposalId, String actor, String note, ApprovalRecord.Decision decision) {
        return require(ownerUserId, proposalId).flatMap(p -> {
            if (p.status() != ProposalStatus.AWAITING_APPROVAL) {
                return Mono.error(new ControlPlaneExceptions.Conflict("Proposal is " + p.status() + "; only AWAITING_APPROVAL proposals can be decided"));
            }
            Instant now = clock.instant();
            ProposalStatus next = decision == ApprovalRecord.Decision.APPROVED ? ProposalStatus.APPROVED : ProposalStatus.REJECTED;
            ActionProposal updated = p.withApproval(new ApprovalRecord(decision, actor, now, blankToNull(note)), now).withStatus(next, now);
            // Funnel step 2: the human decided.
            metrics.approval(next.name());
            return proposals.update(updated)
                    .flatMap(saved -> audit.record(saved.ownerUserId(), saved.walletId(), saved.id(),
                            decision == ApprovalRecord.Decision.APPROVED ? EV_APPROVED : EV_REJECTED, actor,
                            payload("note", note, "executable", saved.policy() != null && saved.policy().executable())).thenReturn(saved));
        });
    }

    /** Owner-scoped lookup with lazy expiry: an old open proposal flips to EXPIRED when read. */
    public Mono<ActionProposal> require(UUID ownerUserId, UUID proposalId) {
        return proposals.findByIdAndOwner(proposalId, ownerUserId)
                .switchIfEmpty(Mono.error(new ControlPlaneExceptions.NotFound("Proposal " + proposalId)))
                .flatMap(this::expireIfDue);
    }

    public Mono<ActionProposal> save(ActionProposal p) {
        return proposals.update(p);
    }

    public Flux<ActionProposal> listForWallet(UUID walletId, int limit) {
        return proposals.findByWallet(walletId, limit).flatMapSequential(this::expireIfDue);
    }

    public Flux<ActionProposal> listForOwner(UUID ownerUserId, int limit) {
        return proposals.findByOwner(ownerUserId, limit).flatMapSequential(this::expireIfDue);
    }

    private Mono<ActionProposal> expireIfDue(ActionProposal p) {
        boolean open = p.status() == ProposalStatus.AWAITING_APPROVAL || p.status() == ProposalStatus.APPROVED;
        if (!open || p.expiresAt() == null || !clock.instant().isAfter(p.expiresAt())) {
            return Mono.just(p);
        }
        Instant now = clock.instant();
        metrics.approval(ProposalStatus.EXPIRED.name());
        return proposals.update(p.withStatus(ProposalStatus.EXPIRED, now))
                .flatMap(saved -> audit.record(saved.ownerUserId(), saved.walletId(), saved.id(), EV_EXPIRED, "cryptobot-service",
                        payload("expiresAt", saved.expiresAt())).thenReturn(saved));
    }

    /**
     * The {@code asset} label of a plan: the symbol of the leg that gets executed (the SELL leg;
     * a rebalance sells the over-weighted asset into the counter asset). Bounded downstream by the
     * metrics allow-list, so a symbol outside the policy shows up as {@code other}.
     */
    static String assetOf(RebalancePlan plan) {
        if (plan == null || plan.legs().isEmpty()) {
            return null;
        }
        return plan.legs().stream()
                .filter(l -> l.action() == RebalanceLeg.Action.SELL)
                .map(RebalanceLeg::symbol)
                .findFirst()
                .orElse(plan.legs().get(0).symbol());
    }

    static Map<String, Object> payload(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            if (kv[i + 1] != null) {
                m.put(String.valueOf(kv[i]), kv[i + 1] instanceof Enum<?> e ? e.name() : kv[i + 1] instanceof List<?> l ? l : kv[i + 1].toString());
            }
        }
        return m;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
