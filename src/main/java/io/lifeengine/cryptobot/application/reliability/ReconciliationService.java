package io.lifeengine.cryptobot.application.reliability;

import io.lifeengine.cryptobot.adapters.solana.SolanaCluster;
import io.lifeengine.cryptobot.adapters.solana.SolanaRpcClient;
import io.lifeengine.cryptobot.application.controlplane.AuditService;
import io.lifeengine.cryptobot.application.controlplane.ControlPlaneExceptions;
import io.lifeengine.cryptobot.application.controlplane.ExecutionReceipts;
import io.lifeengine.cryptobot.application.controlplane.ExecutionService;
import io.lifeengine.cryptobot.application.controlplane.ProposalService;
import io.lifeengine.cryptobot.domain.reliability.DeadLetter;
import io.lifeengine.cryptobot.domain.reliability.TradeEvents;
import io.lifeengine.cryptobot.domain.transactions.ActionProposal;
import io.lifeengine.cryptobot.domain.transactions.ExecutionRecord;
import io.lifeengine.cryptobot.domain.transactions.ProposalStatus;
import io.lifeengine.cryptobot.domain.transactions.ProposalTransition;
import io.lifeengine.cryptobot.infrastructure.persistence.controlplane.ActionProposalRepository;
import io.lifeengine.cryptobot.infrastructure.persistence.controlplane.DeadLetterRepository;
import io.lifeengine.cryptobot.observability.CryptobotMetrics;
import io.lifeengine.cryptobot.observability.ErrorCode;
import io.lifeengine.cryptobot.observability.LogContext;
import io.lifeengine.cryptobot.observability.LogFields;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * Resolves in-flight trades against the chain (KAN-403 §31 "trade incierto → reconciliación").
 * Runs at startup and periodically ({@link ReconciliationJob}); {@link #reconcile} is also
 * callable directly.
 *
 * <p>Rules, in order, for a row in {@code EXECUTING} or {@code SUBMITTED}:
 * <ol>
 *   <li>no signature persisted ⇒ the process died before the signer answered (or before we wrote
 *       its answer): nothing was ever broadcast ⇒ {@code FAILED}, no retry;
 *   <li>signature on the chain with an error ⇒ {@code FAILED}; confirmed/finalized ⇒ {@code EXECUTED};
 *   <li>signature not found and the current block height is past {@code lastValidBlockHeight} ⇒ the
 *       chain can never include it. <b>KAN-571:</b> the operation is retried <em>idempotently</em>
 *       — same {@code operationId}, fresh blockhash, new signature, through the full pipeline
 *       (re-simulation, validator, signer) — up to {@code maxRetries} times; past that ⇒ dead
 *       letter {@code retries_exhausted}. A {@code SUBMITTED} row here is a mismatch (we believed
 *       the node had it) and is counted as such before the retry;
 *   <li>otherwise (still inside the blockhash window, or the RPC did not answer) ⇒ leave it, count
 *       the attempt; past {@code maxAttempts} ⇒ dead letter {@code ambiguous}, with an alert.
 * </ol>
 * Every write is a guarded {@link ProposalTransition}: if a live request moved the row first, the
 * reconciler's commit fails with {@code StaleProposal} and it simply moves on.
 *
 * <p>{@link #settle} is the human's version of the same verdict (KAN-501 resolve): the chain is
 * asked once, ignoring the attempt ceiling; a never-seen expired signature is closed as
 * {@code FAILED} instead of retried; and "no verdict" leaves the row untouched.
 */
@Service
public class ReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);
    public static final String ACTOR = "reconciliation";
    public static final String EV_RECONCILED = "RECONCILED";
    public static final String EV_AMBIGUOUS = "RECONCILIATION_AMBIGUOUS";
    /** KAN-571: the idempotent retries of an operation ran out; a human owns it now. */
    public static final String EV_RETRIES_EXHAUSTED = "RECONCILIATION_RETRIES_EXHAUSTED";

    /** Bounded {@code reason} label of {@code cryptobot_dead_letter_total} and {@code payload.kind} of the letter. */
    public static final String KIND_AMBIGUOUS = "ambiguous";
    public static final String KIND_RETRIES_EXHAUSTED = "retries_exhausted";
    public static final String KIND_INCONSISTENT = "inconsistent";

    public enum Result {
        /** Row already consistent with the chain (still pending inside its window). */
        MATCHED,
        /** Row moved to the terminal state the chain proves. */
        CORRECTED,
        /** KAN-571: blockhash expired unseen ⇒ re-executed under the same operationId (new signature). */
        RETRIED,
        /** Sent to the dead-letter queue; a human decides. */
        DEAD_LETTERED,
        /** Younger than the grace period, already dead-lettered, or ({@link #settle}) no verdict yet: not touched. */
        SKIPPED
    }

    private final ActionProposalRepository proposals;
    private final DeadLetterRepository deadLetters;
    private final SolanaRpcClient rpc;
    private final AuditService audit;
    private final CryptobotMetrics metrics;
    private final ReliabilityProperties.Reconciliation config;
    private final ExecutionReceipts executionReceipts;
    private final ExecutionService execution;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public ReconciliationService(ActionProposalRepository proposals, DeadLetterRepository deadLetters, SolanaRpcClient rpc, AuditService audit,
            CryptobotMetrics metrics, ReliabilityProperties properties, ExecutionReceipts executionReceipts, ExecutionService execution) {
        this(proposals, deadLetters, rpc, audit, metrics, properties, executionReceipts, execution, Clock.systemUTC());
    }

    /** Clock injectable for tests (grace and attempt timestamps). */
    public ReconciliationService(ActionProposalRepository proposals, DeadLetterRepository deadLetters, SolanaRpcClient rpc, AuditService audit,
            CryptobotMetrics metrics, ReliabilityProperties properties, ExecutionReceipts executionReceipts, ExecutionService execution, Clock clock) {
        this.proposals = proposals;
        this.deadLetters = deadLetters;
        this.rpc = rpc;
        this.audit = audit;
        this.metrics = metrics;
        this.config = properties.reconciliation();
        this.executionReceipts = executionReceipts;
        this.execution = execution;
        this.clock = clock;
    }

    /** One sweep over every in-flight row older than the grace period. Returns how many were looked at. */
    public Mono<Long> sweep() {
        Instant cutoff = clock.instant().minus(config.grace());
        return proposals.findInFlight(cutoff, config.batchSize())
                .concatMap(p -> reconcile(p)
                        .onErrorResume(ControlPlaneExceptions.StaleProposal.class, ex -> {
                            log.info("reconciliation_skipped_stale proposalId={}", p.id(), LogFields.event("reconciliation"), LogFields.status("skipped"));
                            return Mono.just(Result.SKIPPED);
                        })
                        .onErrorResume(ex -> {
                            log.warn("reconciliation_row_failed proposalId={} error={}", p.id(), ex.toString(),
                                    LogFields.event("reconciliation"), LogFields.status("failed"), ErrorCode.RECONCILIATION_ROW_FAILED.kv(), ex);
                            return Mono.just(Result.SKIPPED);
                        }))
                .count()
                .flatMap(n -> deadLetters.countUnresolved().doOnNext(metrics::dlqSize).thenReturn(n))
                .doOnNext(n -> {
                    if (n > 0) {
                        log.info("reconciliation_sweep rows={}", n);
                    }
                });
    }

    public Mono<Result> reconcile(ActionProposal p) {
        // KAN-573: el barrido corre sin request; proposalId/operationId entran al MDC por fila (LogContext).
        return reconcile(p, false).doOnNext(r -> metrics.reconciliation(r.name()))
                .contextWrite(ctx -> LogContext.proposal(ctx, p.id(), p.operationId()));
    }

    /**
     * KAN-501 resolve: the human's verdict. The chain is asked once regardless of the attempt
     * ceiling; the row is closed to what the chain proves — {@code EXECUTED}, {@code FAILED}, or
     * {@code FAILED} when the signature was never seen and can no longer land — and is left
     * untouched ({@link Result#SKIPPED}) when there is no verdict yet (inside the blockhash window,
     * or the RPC is down). Never retries: that is what {@code requeue} is for.
     */
    public Mono<Result> settle(ActionProposal p, String actor, String note) {
        if (!p.status().inFlight()) {
            return Mono.just(Result.SKIPPED);
        }
        ExecutionRecord exec = p.execution();
        if (exec == null || !exec.hasSignature()) {
            return fail(p, "Closed by " + actor + ": nothing was ever broadcast (no signature persisted)" + (note == null ? "" : " — " + note), null, false, actor);
        }
        return verdict(p, exec).flatMap(v -> {
            if (v instanceof Verdict.Confirmed c) {
                return executed(p, c.confirmation(), actor);
            }
            if (v instanceof Verdict.Failed f) {
                return fail(p, f.reason(), f.confirmation(), false, actor);
            }
            if (v instanceof Verdict.Expired e) {
                return fail(p, "Closed by " + actor + ": " + e.detail() + (note == null ? "" : " — " + note), null, e.mismatch(), actor);
            }
            log.info("reconciliation_settle_no_verdict proposalId={} why={}", p.id(), ((Verdict.Pending) v).why());
            return Mono.just(Result.SKIPPED);
        });
    }

    private Mono<Result> reconcile(ActionProposal p, boolean ignoreCeiling) {
        if (!p.status().inFlight()) {
            return Mono.just(Result.SKIPPED);
        }
        ExecutionRecord exec = p.execution();
        if (!ignoreCeiling && exec != null && exec.reconciliationAttempts() >= config.maxAttempts()) {
            return Mono.just(Result.SKIPPED); // already dead-lettered; a human owns it now
        }
        if (exec == null || !exec.hasSignature()) {
            if (p.status() == ProposalStatus.SUBMITTED) {
                return deadLetter(p, KIND_INCONSISTENT, "SUBMITTED without a signature: the row is inconsistent", Map.of());
            }
            return fail(p, "Process died before the transaction was signed; nothing reached the chain", null, false, ACTOR);
        }
        return verdict(p, exec).flatMap(v -> {
            if (v instanceof Verdict.Confirmed c) {
                return executed(p, c.confirmation(), ACTOR);
            }
            if (v instanceof Verdict.Failed f) {
                return fail(p, f.reason(), f.confirmation(), false, ACTOR);
            }
            if (v instanceof Verdict.Expired e) {
                return expired(p, exec, e);
            }
            return stillPending(p, ((Verdict.Pending) v).why());
        });
    }

    /**
     * The chain's answer, with every RPC failure folded into a verdict; only then is the row
     * written — so a DB error is never mistaken for "RPC unavailable" and retried.
     */
    private Mono<Verdict> verdict(ActionProposal p, ExecutionRecord exec) {
        SolanaCluster cluster = SolanaCluster.parse(p.cluster());
        return rpc.getSignatureStatus(cluster, exec.signature())
                .flatMap(status -> {
                    if (status.failed()) {
                        return Mono.just(new Verdict.Failed("On-chain error: " + status.error(), status.confirmationStatus()));
                    }
                    if ("confirmed".equals(status.confirmationStatus()) || "finalized".equals(status.confirmationStatus())) {
                        return Mono.just(new Verdict.Confirmed(status.confirmationStatus()));
                    }
                    boolean seen = status.confirmationStatus() != null; // "processed": the node has it, not yet confirmed
                    if (seen || exec.lastValidBlockHeight() == null) {
                        return Mono.just(new Verdict.Pending(seen ? "processed, waiting for confirmation" : "no lastValidBlockHeight to judge expiry"));
                    }
                    return rpc.getBlockHeight(cluster).<Verdict>map(height -> {
                        if (height > exec.lastValidBlockHeight()) {
                            return new Verdict.Expired("signature never seen on chain and blockhash expired (block height " + height + " > "
                                    + exec.lastValidBlockHeight() + ")", p.status() == ProposalStatus.SUBMITTED);
                        }
                        return new Verdict.Pending("not yet seen; blockhash valid until height " + exec.lastValidBlockHeight() + " (now " + height + ")");
                    });
                })
                .onErrorResume(ex -> {
                    log.warn("reconciliation_rpc_failed proposalId={} signature={} error={}", p.id(), exec.signature(), ex.toString(),
                            LogFields.event("reconciliation"), LogFields.status("pending"), LogFields.stage("reconciliation"), ErrorCode.SOLANA_RPC.kv());
                    return Mono.just(new Verdict.Pending("RPC unavailable: " + ex.getMessage()));
                });
    }

    private sealed interface Verdict {
        record Confirmed(String confirmation) implements Verdict {}

        record Failed(String reason, String confirmation) implements Verdict {}

        /** Never seen, can never land. {@code mismatch}: the node had said it accepted it (SUBMITTED). */
        record Expired(String detail, boolean mismatch) implements Verdict {}

        record Pending(String why) implements Verdict {}
    }

    /** KAN-571 rule 3: the previous bytes can never be included ⇒ retry under the same operationId, or give up. */
    private Mono<Result> expired(ActionProposal p, ExecutionRecord exec, Verdict.Expired e) {
        if (e.mismatch()) {
            metrics.reconciliationMismatch();
            log.warn("reconciliation_mismatch proposalId={} signature={} reason=submitted-but-never-seen", p.id(), exec.signature(),
                    LogFields.event("reconciliation_mismatch"), LogFields.status("mismatch"), ErrorCode.RECONCILIATION_MISMATCH.kv());
        }
        int retries = exec.retries();
        if (retries >= config.maxRetries()) {
            String reason = "Signature never seen on chain and blockhash expired; " + retries + " idempotent retr" + (retries == 1 ? "y" : "ies")
                    + " exhausted (max " + config.maxRetries() + ") — nothing is on the chain for operation " + p.operationId() + " (" + e.detail() + ")";
            return deadLetter(p, KIND_RETRIES_EXHAUSTED, reason, ProposalService.payload("signature", exec.signature(), "previousSignature", exec.previousSignature(),
                    "retries", retries, "lastValidBlockHeight", exec.lastValidBlockHeight(), "operationId", p.operationId()));
        }
        log.warn("reconciliation_retry proposalId={} operationId={} retry={} of {} previousSignature={} why={}", p.id(), p.operationId(), retries + 1,
                config.maxRetries(), exec.signature(), e.detail(),
                LogFields.event("reconciliation_retry"), LogFields.status("retrying"), ErrorCode.RECONCILIATION_RETRY.kv());
        metrics.tradeReconciled("corrected");
        return execution.retry(p, ACTOR)
                .doOnNext(after -> log.info("reconciliation_retried proposalId={} operationId={} status={} signature={}", p.id(), p.operationId(), after.status(),
                        after.execution() == null ? null : after.execution().signature(),
                        LogFields.event("reconciliation_retried"), LogFields.status(after.status().name().toLowerCase(java.util.Locale.ROOT))))
                .thenReturn(Result.RETRIED);
    }

    private Mono<Result> executed(ActionProposal p, String confirmation, String actor) {
        Instant now = clock.instant();
        ExecutionRecord prev = p.execution();
        ExecutionRecord rec = prev.withStatus(ExecutionRecord.EXECUTED, now, confirmation, null).withReconciliationAttempt(now);
        if (rec.submittedAt() == null) {
            rec = rec.withSubmitted(now).withStatus(ExecutionRecord.EXECUTED, now, confirmation, null);
        }
        ActionProposal next = p.withExecution(rec, now).withStatus(ProposalStatus.EXECUTED, now);
        String asset = assetOf(p);
        metrics.tradeConfirmed(confirmation, asset);
        metrics.tradeReconciled("corrected");
        log.info("reconciliation_corrected proposalId={} from={} to=EXECUTED signature={} confirmation={} by={}", p.id(), p.status(), prev.signature(), confirmation, actor,
                LogFields.event("reconciliation_corrected"), LogFields.status("executed"));
        return proposals.commit(ProposalTransition.from(p, next)
                        .audit(audit.event(p.ownerUserId(), p.walletId(), p.id(), EV_RECONCILED, actor,
                                        ProposalService.payload("from", p.status(), "to", ProposalStatus.EXECUTED, "signature", prev.signature(), "confirmation", confirmation)),
                                audit.event(p.ownerUserId(), p.walletId(), p.id(), ExecutionService.EV_EXECUTED, actor,
                                        ProposalService.payload("signature", prev.signature(), "explorerUrl", prev.explorerUrl(), "confirmation", confirmation)))
                        .publish(ProposalService.tradeEvent(next, TradeEvents.CONFIRMED, now,
                                ProposalService.payload("signature", prev.signature(), "explorerUrl", prev.explorerUrl(), "confirmation", confirmation, "reconciled", true))))
                // KAN-391: the same EXECUTION receipt the synchronous path would have left.
                .flatMap(terminal -> executionReceipts.receiptFor(terminal, prev.submittedAt()))
                .thenReturn(Result.CORRECTED);
    }

    private Mono<Result> fail(ActionProposal p, String reason, String confirmation, boolean mismatch, String actor) {
        Instant now = clock.instant();
        ExecutionRecord prev = p.execution();
        ExecutionRecord rec = prev == null
                ? new ExecutionRecord(ExecutionRecord.FAILED, null, null, null, now, null, null, reason, null, null, 1, now)
                : prev.withStatus(ExecutionRecord.FAILED, null, confirmation, reason).withReconciliationAttempt(now);
        ActionProposal next = p.withExecution(rec, now).withStatus(ProposalStatus.FAILED, now);
        metrics.tradeFailed(prev != null && prev.hasSignature() ? CryptobotMetrics.FailureStage.ONCHAIN : CryptobotMetrics.FailureStage.OTHER, assetOf(p));
        metrics.tradeReconciled("corrected");
        if (mismatch) {
            metrics.reconciliationMismatch();
        }
        log.warn("reconciliation_corrected proposalId={} from={} to=FAILED reason={} by={}", p.id(), p.status(), reason, actor,
                LogFields.event("reconciliation_corrected"), LogFields.status("failed"), LogFields.stage("reconciliation"), ErrorCode.SOLANA_TX_FAILED.kv());
        return proposals.commit(ProposalTransition.from(p, next)
                        .audit(audit.event(p.ownerUserId(), p.walletId(), p.id(), EV_RECONCILED, actor,
                                        ProposalService.payload("from", p.status(), "to", ProposalStatus.FAILED, "reason", reason, "mismatch", mismatch)),
                                audit.event(p.ownerUserId(), p.walletId(), p.id(), ExecutionService.EV_FAILED, actor, ProposalService.payload("error", reason)))
                        .publish(ProposalService.tradeEvent(next, TradeEvents.FAILED, now,
                                ProposalService.payload("error", reason, "stage", "reconciliation", "signature", rec.signature(), "mismatch", mismatch))))
                .flatMap(terminal -> executionReceipts.receiptFor(terminal, prev == null ? p.updatedAt() : prev.submittedAt()))
                .thenReturn(Result.CORRECTED);
    }

    private Mono<Result> stillPending(ActionProposal p, String why) {
        Instant now = clock.instant();
        ExecutionRecord rec = p.execution().withReconciliationAttempt(now);
        if (rec.reconciliationAttempts() >= config.maxAttempts()) {
            return deadLetter(p, KIND_AMBIGUOUS, "No verdict after " + rec.reconciliationAttempts() + " reconciliation attempts (" + why + ")",
                    ProposalService.payload("signature", rec.signature(), "lastValidBlockHeight", rec.lastValidBlockHeight(), "operationId", p.operationId()));
        }
        metrics.tradeReconciled("matched");
        log.info("reconciliation_pending proposalId={} attempt={} why={}", p.id(), rec.reconciliationAttempts(), why,
                LogFields.event("reconciliation"), LogFields.status("pending"));
        return proposals.commit(ProposalTransition.from(p, p.withExecution(rec, now))).thenReturn(Result.MATCHED);
    }

    /**
     * Ambiguous or exhausted: the row is left in flight (it is the truth we have), the attempt
     * counter is set to the ceiling so the sweep skips it, and a dead letter carries the alert.
     * {@code kind} is the bounded label of {@code cryptobot_dead_letter_total{reason}} and travels
     * in the letter's payload so the API and the runbook can tell the cases apart.
     */
    private Mono<Result> deadLetter(ActionProposal p, String kind, String reason, Map<String, Object> details) {
        Instant now = clock.instant();
        Map<String, Object> payload = new LinkedHashMap<>(details);
        payload.put("kind", kind);
        payload.put("status", p.status().name());
        payload.put("walletId", p.walletId().toString());
        DeadLetter letter = DeadLetter.of(DeadLetter.Source.RECONCILIATION, p.id(), p.id(), p.ownerUserId(), reason, payload, now);
        log.error("reconciliation_dead_letter proposalId={} status={} kind={} reason={} — DLQ, human decision required", p.id(), p.status(), kind, reason,
                LogFields.event("dead_letter"), LogFields.status(kind), ErrorCode.DEAD_LETTERED.kv());
        ExecutionRecord prev = p.execution();
        ExecutionRecord rec = prev == null
                ? new ExecutionRecord(p.status() == ProposalStatus.SUBMITTED ? ExecutionRecord.SUBMITTED : ExecutionRecord.SIGNED, null, null, null, null, null, null, reason, null, null, config.maxAttempts(), now)
                : prev.withReconciliationAttempts(config.maxAttempts(), reason, now);
        String eventType = KIND_RETRIES_EXHAUSTED.equals(kind) ? EV_RETRIES_EXHAUSTED : EV_AMBIGUOUS;
        metrics.deadLetter(kind);
        return deadLetters.append(letter)
                .then(proposals.commit(ProposalTransition.from(p, p.withExecution(rec, now))
                        .audit(audit.event(p.ownerUserId(), p.walletId(), p.id(), eventType, ACTOR, ProposalService.payload("kind", kind, "reason", reason, "deadLetterId", letter.id())))))
                .then(deadLetters.countUnresolved().doOnNext(metrics::dlqSize))
                .thenReturn(Result.DEAD_LETTERED);
    }

    private static String assetOf(ActionProposal p) {
        return ProposalService.assetOf(p.plan());
    }
}
