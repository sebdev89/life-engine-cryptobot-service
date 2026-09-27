package io.lifeengine.cryptobot.application.controlplane;

import io.lifeengine.cryptobot.application.oracle.PriceOracleService;
import io.lifeengine.cryptobot.application.receipt.ReceiptDraft;
import io.lifeengine.cryptobot.application.receipt.ReceiptService;
import io.lifeengine.cryptobot.core.oracle.OracleReading;
import io.lifeengine.cryptobot.core.policy.PolicyDecision;
import io.lifeengine.cryptobot.core.policy.PolicyVerdict;
import io.lifeengine.cryptobot.core.receipts.ReceiptEdge;
import io.lifeengine.cryptobot.core.reliability.OutboxEvent;
import io.lifeengine.cryptobot.core.reliability.TradeEvents;
import io.lifeengine.cryptobot.trading.risk.RiskReport;
import io.lifeengine.cryptobot.trading.strategy.RebalanceIntent;
import io.lifeengine.cryptobot.trading.strategy.RebalanceLeg;
import io.lifeengine.cryptobot.trading.strategy.RebalancePlan;
import io.lifeengine.cryptobot.core.execution.ActionProposal;
import io.lifeengine.cryptobot.core.execution.ApprovalRecord;
import io.lifeengine.cryptobot.core.execution.ProposalStatus;
import io.lifeengine.cryptobot.core.execution.ProposalTransition;
import io.lifeengine.cryptobot.core.wallet.Wallet;
import io.lifeengine.cryptobot.infrastructure.persistence.controlplane.ActionProposalRepository;
import io.lifeengine.cryptobot.integration.signer.SignerClient;
import io.lifeengine.cryptobot.integration.validator.ValidatorClient;
import io.lifeengine.cryptobot.observability.CryptobotMetrics;
import io.lifeengine.cryptobot.observability.ErrorCode;
import io.lifeengine.cryptobot.observability.LogContext;
import io.lifeengine.cryptobot.observability.LogFields;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
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
 * Each step is one atomic {@link ProposalTransition}: state, audit trail and outbox event are
 * written together or not at all (KAN-403). Nothing here can sign or send.
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
    /** KAN-438 (paper §19): a human cancelled an APPROVED proposal inside its timelock. */
    public static final String EV_CANCELLED = "CANCELLED";
    static final String SERVICE_ACTOR = "cryptobot-service";

    private final ActionProposalRepository proposals;
    private final PortfolioService portfolio;
    private final RebalancePlanner planner;
    private final RiskEngine riskEngine;
    private final SimulationService simulation;
    private final PolicyEngine policy;
    private final SignerClient signer;
    private final ValidatorClient validator;
    private final PriceOracleService oracle;
    private final AuditService audit;
    private final CryptobotMetrics metrics;
    private final ReceiptService receipts;
    private final Receipts receiptOf;
    private final AnalysisReuse analysisReuse;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public ProposalService(
            ActionProposalRepository proposals,
            PortfolioService portfolio,
            RebalancePlanner planner,
            RiskEngine riskEngine,
            SimulationService simulation,
            PolicyEngine policy,
            SignerClient signer,
            ValidatorClient validator,
            PriceOracleService oracle,
            AuditService audit,
            CryptobotMetrics metrics,
            ReceiptService receipts,
            Receipts receiptOf,
            AnalysisReuse analysisReuse) {
        this(proposals, portfolio, planner, riskEngine, simulation, policy, signer, validator, oracle, audit, metrics, receipts, receiptOf, analysisReuse, Clock.systemUTC());
    }

    ProposalService(
            ActionProposalRepository proposals,
            PortfolioService portfolio,
            RebalancePlanner planner,
            RiskEngine riskEngine,
            SimulationService simulation,
            PolicyEngine policy,
            SignerClient signer,
            ValidatorClient validator,
            PriceOracleService oracle,
            AuditService audit,
            CryptobotMetrics metrics,
            ReceiptService receipts,
            Receipts receiptOf,
            AnalysisReuse analysisReuse,
            Clock clock) {
        this.proposals = proposals;
        this.portfolio = portfolio;
        this.planner = planner;
        this.riskEngine = riskEngine;
        this.simulation = simulation;
        this.policy = policy;
        this.signer = signer;
        this.validator = validator;
        this.oracle = oracle;
        this.audit = audit;
        this.metrics = metrics;
        this.receipts = receipts;
        this.receiptOf = receiptOf;
        this.analysisReuse = analysisReuse;
        this.clock = clock;
    }

    /**
     * KAN-391: three receipts on the way to the human. {@code STRATEGY} (the plan, L1; parents: the
     * snapshot it was planned on and, if the caller passed the advisor's {@code runtimeRunId}, the
     * {@code MARKET_ANALYSIS} that suggested it), {@code RISK_DECISION} over the projected portfolio
     * (VALIDATES the strategy, L1) and {@code SIMULATION} (DERIVES_FROM the strategy).
     *
     * <p>KAN-393: without a {@code runtimeRunId}, the strategy may instead <em>reuse</em> the
     * wallet's latest {@code MARKET_ANALYSIS} — same asset, younger than the reuse window — and
     * says so with a {@code REUSES} edge ({@link AnalysisReuse}). The audit event records which.
     */
    public Mono<ActionProposal> createRebalance(Wallet wallet, String actor, RebalanceIntent intent, String reasoningSummary, UUID runtimeRunId) {
        return portfolio.latest(wallet).flatMap(view -> {
            RebalancePlan plan = planner.plan(view.snapshot(), intent);
            if (plan.isNoop()) {
                metrics.strategyCreated("noop", null);
                return Mono.error(new ControlPlaneExceptions.InvalidRequest("NOOP", "Portfolio is already within the requested targets"));
            }
            metrics.strategyCreated("proposed", assetOf(plan));
            io.lifeengine.cryptobot.trading.portfolio.PortfolioSnapshot projected = planner.project(view.snapshot(), plan);
            RiskReport riskAfter = riskEngine.evaluate(projected, null);
            if (riskAfter.overall() == io.lifeengine.cryptobot.trading.risk.RiskSeverity.HIGH) {
                // KAN-573: riesgo alto del portfolio resultante — no bloquea por sí solo (la policy decide), pero se busca en Loki.
                log.warn("proposal_risk_high wallet={} score={} findings={}", wallet.id(), riskAfter.score(),
                        riskAfter.findings().stream().map(f -> f.code()).toList(),
                        LogFields.event("risk_evaluated"), LogFields.status("high"), ErrorCode.RISK_HIGH.kv());
            }
            Instant now = clock.instant();
            String title = "Rebalance: " + String.join(", ", intent.targetWeights().keySet()) + " → " + intent.targetWeights().values();
            ActionProposal p = new ActionProposal(UUID.randomUUID(), wallet.id(), wallet.ownerUserId(), wallet.address(), wallet.cluster().id(),
                    ProposalStatus.PROPOSED, "REBALANCE", title, blankToNull(reasoningSummary), actor, intent, plan, view.risk(), riskAfter,
                    null, null, null, null, null, runtimeRunId, view.snapshot().id(), now.plus(policy.properties().proposalTtl()), now, now, null, 0);
            String tenant = Receipts.tenantOf(wallet.ownerUserId());
            Mono<Optional<String>> snapshotReceipt = receipts.byNonce(tenant, view.snapshot().id().toString())
                    .map(r -> Optional.of(r.receiptHash())).defaultIfEmpty(Optional.empty());
            // The analysis behind this strategy: the run the caller named (DERIVES_FROM), or — without one —
            // the wallet's latest analysis if it is recent and about the same asset (REUSES, KAN-393).
            Mono<AnalysisLink> analysis = runtimeRunId != null
                    ? receipts.byNonce(tenant, runtimeRunId.toString()).map(r -> new AnalysisLink(r.receiptHash(), ReceiptEdge.Role.DERIVES_FROM))
                            .defaultIfEmpty(AnalysisLink.NONE)
                    : analysisReuse.find(wallet, intent).map(r -> r.map(x -> new AnalysisLink(x.receiptHash(), ReceiptEdge.Role.REUSES)).orElse(AnalysisLink.NONE));
            return Mono.zip(snapshotReceipt, analysis).flatMap(refs -> {
                AnalysisLink link = refs.getT2();
                return proposals.insert(p)
                        .flatMap(saved -> audit.record(saved.ownerUserId(), saved.walletId(), saved.id(), EV_CREATED, actor,
                                payload("plan", plan.summary(), "turnoverUsd", plan.turnoverUsd(), "riskBefore", view.risk().overall(), "riskAfter", riskAfter.overall(),
                                        "runtimeRunId", runtimeRunId, "analysisReceipt", link.receiptHash(), "analysisRole", link.receiptHash() == null ? null : link.role().name()))
                                .thenReturn(saved))
                        .flatMap(saved -> {
                            ReceiptDraft strategyDraft = receiptOf.strategy(wallet, saved, view.snapshot(), intent, plan, refs.getT1().orElse(null), link.receiptHash());
                            if (link.role() == ReceiptEdge.Role.REUSES) {
                                strategyDraft = strategyDraft.withRole(link.receiptHash(), ReceiptEdge.Role.REUSES);
                                metrics.artifactReuse(false);
                                log.info("analysis_reused proposal={} analysis={}", saved.id(), link.receiptHash());
                            }
                            return receipts.issue(strategyDraft)
                                    .flatMap(strategy -> receipts.issue(receiptOf.riskDecisionAfter(wallet, saved, projected, riskAfter, strategy.receiptHash()))
                                            .thenReturn(strategy.receiptHash()))
                                    .flatMap(strategyHash -> simulate(saved, wallet, view.snapshot(), strategyHash));
                        })
                        .flatMap(sim -> evaluatePolicy(sim.proposal(), wallet, view.snapshot().capturedAt(), sim.strategyReceipt(), sim.simulationReceipt()));
            })
            // KAN-573: de acá al veredicto de policy, cada línea dice de qué propuesta (y de qué corrida del Runtime) habla.
            .contextWrite(ctx -> LogContext.write(LogContext.write(ctx, LogContext.PROPOSAL_ID, p.id()), LogContext.RUNTIME_RUN_ID, runtimeRunId));
        });
    }

    /** Which MARKET_ANALYSIS a strategy points at and how; {@link #NONE} when there is none. */
    private record AnalysisLink(String receiptHash, ReceiptEdge.Role role) {
        static final AnalysisLink NONE = new AnalysisLink(null, ReceiptEdge.Role.DERIVES_FROM);
    }

    /** A simulated proposal and the receipts it descends from — what the Decision Receipt (KAN-572) points at. */
    private record Simulated(ActionProposal proposal, String strategyReceipt, String simulationReceipt) {}

    private Mono<Simulated> simulate(ActionProposal p, Wallet wallet, io.lifeengine.cryptobot.trading.portfolio.PortfolioSnapshot snapshot, String strategyReceipt) {
        Instant started = clock.instant();
        return simulation.simulate(wallet, snapshot, p.plan())
                .flatMap(sim -> {
                    Instant now = clock.instant();
                    ActionProposal next = p.withSimulation(sim.outcome(), sim.transaction(), now).withStatus(ProposalStatus.SIMULATED, now);
                    return proposals.commit(ProposalTransition.from(p, next)
                            .audit(audit.event(p.ownerUserId(), p.walletId(), p.id(), EV_SIMULATED, SERVICE_ACTOR,
                                    payload("onchainOk", sim.outcome().onchain().ok(), "onchainError", sim.outcome().onchain().error(),
                                            "unitsConsumed", sim.outcome().onchain().unitsConsumed(),
                                            "expectedOut", sim.outcome().economic() == null ? null : sim.outcome().economic().expectedBuyAmount(),
                                            "lamports", sim.transaction() == null ? null : sim.transaction().lamports()))))
                            .flatMap(simulated -> receipts.issue(receiptOf.simulation(simulated, sim.outcome(), sim.transaction(), strategyReceipt, started))
                                    .map(r -> new Simulated(simulated, strategyReceipt, r.receiptHash())));
                });
    }

    /**
     * The authoritative state {@code S} the policy needs comes from this wallet's own history:
     * the last execution (cooldown) and the notional executed in the last 24 h (daily exposure),
     * from the most recent proposals — and, since KAN-439, from a fresh multi-source reading of
     * every asset the plan touches: the prices {@code S} is allowed to contain, and the oracle age.
     */
    private Mono<ActionProposal> evaluatePolicy(ActionProposal p, Wallet wallet, Instant pricesAsOf, String strategyReceipt, String simulationReceipt) {
        Instant now = clock.instant();
        Mono<OracleReading> reading = oracle.read(assetsOf(p.plan()));
        Mono<PolicyEngine.WalletState> state = proposals.findByWallet(wallet.id(), 20)
                .filter(x -> x.status() == ProposalStatus.EXECUTED && x.execution() != null && x.execution().submittedAt() != null)
                .collectList()
                .zipWith(reading)
                .map(t -> {
                    List<ActionProposal> executed = t.getT1();
                    Optional<Instant> last = executed.stream().map(x -> x.execution().submittedAt()).max(Instant::compareTo);
                    BigDecimal last24h = executed.stream()
                            .filter(x -> !x.execution().submittedAt().isBefore(now.minus(Duration.ofHours(24))))
                            .map(x -> x.plan() == null || x.plan().turnoverUsd() == null ? BigDecimal.ZERO : x.plan().turnoverUsd())
                            .reduce(BigDecimal.ZERO, BigDecimal::add);
                    return new PolicyEngine.WalletState(last, last24h, pricesAsOf, t.getT2());
                });
        return Mono.zip(state, signer.identity(), validator.identity()).flatMap(t -> {
            // KAN-438: the independent validator must be up and on the same H_R, or this is a paper trade.
            PolicyDecision decision = policy.requireValidator(
                    policy.evaluate(p, wallet, t.getT1(), t.getT2()), t.getT3());
            PolicyVerdict verdict = decision.authorization();
            ProposalStatus next = decision.allowed() ? ProposalStatus.AWAITING_APPROVAL : ProposalStatus.BLOCKED_BY_POLICY;
            ActionProposal updated = p.withPolicy(decision, now).withStatus(next, now);
            OracleReading oracleReading = decision.oracle();
            log.info("proposal_policy proposalId={} allowed={} executable={} violations={} executionViolations={} decision={} escalation={} tier={} policyHash={} oracleAccepted={} oracleQuotesHash={}",
                    p.id(), decision.allowed(), decision.executable(), decision.violations().size(), decision.executionViolations().size(),
                    verdict.decision(), verdict.escalation(), verdict.tier(), verdict.policyHash(),
                    oracleReading != null && oracleReading.accepted(), oracleReading == null ? null : oracleReading.quotesHash(),
                    LogFields.event("policy_evaluated"), LogFields.status(next.name().toLowerCase(java.util.Locale.ROOT)));
            if (!decision.allowed()) {
                // KAN-573: el rechazo de policy es la primera parada del demo path; una línea con errorCode y las reglas que dijeron no.
                log.warn("proposal_blocked_by_policy proposalId={} rules={} decision={} failedPredicates={}", p.id(),
                        decision.violations().stream().map(PolicyDecision.Violation::rule).toList(), verdict.decision(), verdict.failedPredicates(),
                        LogFields.event("policy_blocked"), LogFields.status("blocked"), ErrorCode.POLICY_BLOCKED.kv());
            }
            // Funnel step 1: the trade was requested — it either reached the human or policy stopped it.
            metrics.tradeRequested(next.name(), assetOf(p.plan()));
            // Authority layer (KAN-440): which verdict, and — on DENY — which predicates said no.
            metrics.policyVerdict(verdict.decision().name(), verdict.escalation().name());
            verdict.failedPredicates().forEach(pred -> metrics.policyPredicateFailed(pred.name()));
            ProposalTransition transition = ProposalTransition.from(p, updated)
                    .audit(audit.event(p.ownerUserId(), p.walletId(), p.id(), EV_POLICY, SERVICE_ACTOR,
                                    payload("allowed", decision.allowed(), "executable", decision.executable(),
                                            "violations", decision.violations().stream().map(v -> v.rule() + ": " + v.message()).toList(),
                                            "executionViolations", decision.executionViolations().stream().map(v -> v.rule() + ": " + v.message()).toList(),
                                            "rulesApplied", decision.rulesApplied(),
                                            "decision", verdict.decision().name(), "escalation", verdict.escalation().name(), "tier", verdict.tier().name(),
                                            "failedPredicates", verdict.failedPredicates().stream().map(Enum::name).toList(),
                                            "policyVersion", verdict.policyVersion(), "policyHash", verdict.policyHash(),
                                            "inputHash", verdict.inputHash(), "verdictHash", verdict.hash(),
                                            // KAN-439: the state reference — which quotes, under which limits, and whether they agreed.
                                            "oracleAccepted", oracleReading == null ? null : oracleReading.accepted(),
                                            "oracleQuotesHash", oracleReading == null ? null : oracleReading.quotesHash(),
                                            "oracleLimitsHash", oracleReading == null ? null : oracleReading.limitsHash(),
                                            "oracleProblems", oracleReading == null ? null : oracleReading.problems())),
                            audit.event(p.ownerUserId(), p.walletId(), p.id(), decision.allowed() ? EV_AWAITING : EV_BLOCKED, SERVICE_ACTOR,
                                    payload("expiresAt", updated.expiresAt())));
            if (decision.allowed()) {
                transition = transition.publish(tradeEvent(updated, TradeEvents.REQUESTED, now,
                        payload("plan", p.plan().summary(), "turnoverUsd", p.plan().turnoverUsd(), "executable", decision.executable(), "expiresAt", updated.expiresAt())));
            }
            // KAN-572: the Decision Receipt — allowed or blocked, every verdict leaves a signed, L1-verifiable receipt in the DAG.
            return proposals.commit(transition)
                    .flatMap(decided -> receiptOf.policyDecision(wallet, decided, strategyReceipt, simulationReceipt)
                            .map(draft -> receipts.issue(draft)
                                    .doOnNext(r -> log.info("decision_receipt proposalId={} status={} hash={} blockedBy={}", decided.id(), decided.status(),
                                            r.receiptHash(), decision.violations().stream().map(PolicyDecision.Violation::rule).toList()))
                                    .thenReturn(decided))
                            .orElse(Mono.just(decided)));
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
            boolean approved = decision == ApprovalRecord.Decision.APPROVED;
            ProposalStatus next = approved ? ProposalStatus.APPROVED : ProposalStatus.REJECTED;
            // KAN-438 (paper §19): the timelock starts now; the window is pushed so the lock can be honoured.
            Instant executableAt = approved ? policy.executableAt(now, p.policy()) : null;
            ActionProposal updated = p.withApproval(new ApprovalRecord(decision, actor, now, blankToNull(note), executableAt), now).withStatus(next, now);
            if (approved) {
                Instant windowEnd = executableAt.plus(policy.timelock().executionWindow());
                if (updated.expiresAt() == null || updated.expiresAt().isBefore(windowEnd)) {
                    updated = updated.withExpiresAt(windowEnd, now);
                }
            }
            // Funnel step 2: the human decided.
            metrics.approval(next.name());
            boolean executable = updated.policy() != null && updated.policy().executable();
            return proposals.commit(ProposalTransition.from(p, updated)
                    .audit(audit.event(p.ownerUserId(), p.walletId(), p.id(), approved ? EV_APPROVED : EV_REJECTED, actor,
                            payload("note", note, "executable", executable, "executableAt", executableAt, "expiresAt", updated.expiresAt())))
                    .publish(tradeEvent(updated, approved ? TradeEvents.APPROVED : TradeEvents.REJECTED, now,
                            payload("by", actor, "note", note, "executable", executable, "executableAt", executableAt))));
        });
    }

    /**
     * KAN-438 (paper §19): during the timelock a human may cancel. Only an APPROVED proposal that
     * has not started executing; the approval record is kept (who approved is part of the trace)
     * and the row goes to REJECTED with a {@code CANCELLED} audit event and outbox fact.
     */
    public Mono<ActionProposal> cancel(UUID ownerUserId, UUID proposalId, String actor, String note) {
        return require(ownerUserId, proposalId).flatMap(p -> {
            if (p.status() != ProposalStatus.APPROVED) {
                return Mono.error(new ControlPlaneExceptions.Conflict("Proposal is " + p.status() + "; only APPROVED proposals (inside their timelock) can be cancelled"));
            }
            Instant now = clock.instant();
            Instant executableAt = policy.executableAt(p);
            ActionProposal updated = p.withStatus(ProposalStatus.REJECTED, now);
            metrics.approval("cancelled");
            return proposals.commit(ProposalTransition.from(p, updated)
                    .audit(audit.event(p.ownerUserId(), p.walletId(), p.id(), EV_CANCELLED, actor,
                            payload("note", note, "executableAt", executableAt, "insideTimelock", executableAt != null && now.isBefore(executableAt))))
                    .publish(tradeEvent(updated, TradeEvents.CANCELLED, now, payload("by", actor, "note", note, "executableAt", executableAt))));
        });
    }

    /** Owner-scoped lookup with lazy expiry: an old open proposal flips to EXPIRED when read. */
    public Mono<ActionProposal> require(UUID ownerUserId, UUID proposalId) {
        return proposals.findByIdAndOwner(proposalId, ownerUserId)
                .switchIfEmpty(Mono.error(new ControlPlaneExceptions.NotFound("Proposal " + proposalId)))
                .flatMap(this::expireIfDue);
    }

    /** One atomic step (state + audit + outbox). The execution path and the reconciler go through here. */
    public Mono<ActionProposal> commit(ProposalTransition transition) {
        return proposals.commit(transition);
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
        ActionProposal expired = p.withStatus(ProposalStatus.EXPIRED, now);
        return proposals.commit(ProposalTransition.from(p, expired)
                        .audit(audit.event(p.ownerUserId(), p.walletId(), p.id(), EV_EXPIRED, SERVICE_ACTOR, payload("expiresAt", p.expiresAt())))
                        .publish(tradeEvent(expired, TradeEvents.EXPIRED, now, payload("expiresAt", p.expiresAt()))))
                // Two readers expiring the same row at once: the second one loses the guard and just reads.
                .onErrorResume(ControlPlaneExceptions.StaleProposal.class, ex -> proposals.findByIdAndOwner(p.id(), p.ownerUserId()));
    }

    /** The outbox event of a proposal step; always carries the identifiers a consumer needs to correlate. */
    public static OutboxEvent tradeEvent(ActionProposal p, String type, Instant now, Map<String, Object> extra) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("proposalId", p.id().toString());
        body.put("walletId", p.walletId().toString());
        body.put("cluster", p.cluster());
        body.put("status", p.status().name());
        if (p.operationId() != null) {
            body.put("operationId", p.operationId().toString());
        }
        String asset = assetOf(p.plan());
        if (asset != null) {
            body.put("asset", asset);
        }
        body.putAll(extra);
        return OutboxEvent.pending(OutboxEvent.AGGREGATE_PROPOSAL, p.id(), p.ownerUserId(), type, body, now);
    }

    /**
     * The {@code asset} label of a plan: the symbol of the leg that gets executed (the SELL leg;
     * a rebalance sells the over-weighted asset into the counter asset). Bounded downstream by the
     * metrics allow-list, so a symbol outside the policy shows up as {@code other}.
     */
    public static String assetOf(RebalancePlan plan) {
        if (plan == null || plan.legs().isEmpty()) {
            return null;
        }
        return plan.legs().stream()
                .filter(l -> l.action() == RebalanceLeg.Action.SELL)
                .map(RebalanceLeg::symbol)
                .findFirst()
                .orElse(plan.legs().get(0).symbol());
    }

    /** Every symbol a plan depends on — each leg's asset and its counter asset — in first-seen order. */
    public static List<String> assetsOf(RebalancePlan plan) {
        List<String> assets = new java.util.ArrayList<>();
        if (plan == null) {
            return assets;
        }
        for (RebalanceLeg leg : plan.legs()) {
            for (String s : new String[] {leg.symbol(), leg.counterAsset()}) {
                if (s != null && !s.isBlank()) {
                    String u = s.trim().toUpperCase(java.util.Locale.ROOT);
                    if (!assets.contains(u)) {
                        assets.add(u);
                    }
                }
            }
        }
        return assets;
    }

    public static Map<String, Object> payload(Object... kv) {
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
