package io.lifeengine.cryptobot.application.reliability;

import io.lifeengine.cryptobot.adapters.solana.SolanaCluster;
import io.lifeengine.cryptobot.adapters.solana.SolanaRpcClient;
import io.lifeengine.cryptobot.application.controlplane.AuditService;
import io.lifeengine.cryptobot.application.controlplane.ControlPlaneExceptions;
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
 *       chain can never include it ⇒ {@code FAILED}, <b>never re-sent</b>; a {@code SUBMITTED} row
 *       here is a mismatch (we believed the node had it);
 *   <li>otherwise (still inside the blockhash window, or the RPC did not answer) ⇒ leave it, count
 *       the attempt; past {@code maxAttempts} ⇒ dead letter as ambiguous, with an alert.
 * </ol>
 * Every write is a guarded {@link ProposalTransition}: if a live request moved the row first, the
 * reconciler's commit fails with {@code StaleProposal} and it simply moves on.
 */
@Service
public class ReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);
    public static final String ACTOR = "reconciliation";
    public static final String EV_RECONCILED = "RECONCILED";
    public static final String EV_AMBIGUOUS = "RECONCILIATION_AMBIGUOUS";

    public enum Result {
        /** Row already consistent with the chain (still pending inside its window). */
        MATCHED,
        /** Row moved to the terminal state the chain proves. */
        CORRECTED,
        /** Sent to the dead-letter queue; a human decides. */
        DEAD_LETTERED,
        /** Younger than the grace period or already dead-lettered: not touched. */
        SKIPPED
    }

    private final ActionProposalRepository proposals;
    private final DeadLetterRepository deadLetters;
    private final SolanaRpcClient rpc;
    private final AuditService audit;
    private final CryptobotMetrics metrics;
    private final ReliabilityProperties.Reconciliation config;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public ReconciliationService(ActionProposalRepository proposals, DeadLetterRepository deadLetters, SolanaRpcClient rpc, AuditService audit,
            CryptobotMetrics metrics, ReliabilityProperties properties) {
        this(proposals, deadLetters, rpc, audit, metrics, properties, Clock.systemUTC());
    }

    /** Clock injectable for tests (grace and attempt timestamps). */
    public ReconciliationService(ActionProposalRepository proposals, DeadLetterRepository deadLetters, SolanaRpcClient rpc, AuditService audit,
            CryptobotMetrics metrics, ReliabilityProperties properties, Clock clock) {
        this.proposals = proposals;
        this.deadLetters = deadLetters;
        this.rpc = rpc;
        this.audit = audit;
        this.metrics = metrics;
        this.config = properties.reconciliation();
        this.clock = clock;
    }

    /** One sweep over every in-flight row older than the grace period. Returns how many were looked at. */
    public Mono<Long> sweep() {
        Instant cutoff = clock.instant().minus(config.grace());
        return proposals.findInFlight(cutoff, config.batchSize())
                .concatMap(p -> reconcile(p)
                        .onErrorResume(ControlPlaneExceptions.StaleProposal.class, ex -> {
                            log.info("reconciliation_skipped_stale proposalId={}", p.id());
                            return Mono.just(Result.SKIPPED);
                        })
                        .onErrorResume(ex -> {
                            log.warn("reconciliation_row_failed proposalId={} error={}", p.id(), ex.toString());
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
        if (!p.status().inFlight()) {
            return Mono.just(Result.SKIPPED);
        }
        ExecutionRecord exec = p.execution();
        if (exec != null && exec.reconciliationAttempts() >= config.maxAttempts()) {
            return Mono.just(Result.SKIPPED); // already dead-lettered; a human owns it now
        }
        if (exec == null || !exec.hasSignature()) {
            if (p.status() == ProposalStatus.SUBMITTED) {
                return deadLetter(p, "SUBMITTED without a signature: the row is inconsistent", Map.of());
            }
            return fail(p, "Process died before the transaction was signed; nothing reached the chain", null, false);
        }
        SolanaCluster cluster = SolanaCluster.parse(p.cluster());
        // The chain's answer is gathered first, with every RPC failure folded into a verdict; only
        // then is the row written — so a DB error is never mistaken for "RPC unavailable" and retried.
        Mono<Verdict> verdict = rpc.getSignatureStatus(cluster, exec.signature())
                .flatMap(status -> {
                    if (status.failed()) {
                        return Mono.just(new Verdict.Failed("On-chain error: " + status.error(), status.confirmationStatus(), false));
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
                            boolean claimedSubmitted = p.status() == ProposalStatus.SUBMITTED;
                            return new Verdict.Failed("Signature never seen on chain and blockhash expired (block height " + height + " > "
                                    + exec.lastValidBlockHeight() + "); not retried", null, claimedSubmitted);
                        }
                        return new Verdict.Pending("not yet seen; blockhash valid until height " + exec.lastValidBlockHeight() + " (now " + height + ")");
                    });
                })
                .onErrorResume(ex -> {
                    log.warn("reconciliation_rpc_failed proposalId={} signature={} error={}", p.id(), exec.signature(), ex.toString());
                    return Mono.just(new Verdict.Pending("RPC unavailable: " + ex.getMessage()));
                });
        return verdict.flatMap(v -> {
            if (v instanceof Verdict.Confirmed c) {
                return executed(p, c.confirmation());
            }
            if (v instanceof Verdict.Failed f) {
                if (f.mismatch()) {
                    metrics.reconciliationMismatch();
                    log.warn("reconciliation_mismatch proposalId={} signature={} reason=submitted-but-never-seen", p.id(), exec.signature());
                }
                return fail(p, f.reason(), f.confirmation(), f.mismatch());
            }
            return stillPending(p, ((Verdict.Pending) v).why());
        });
    }

    private sealed interface Verdict {
        record Confirmed(String confirmation) implements Verdict {}

        record Failed(String reason, String confirmation, boolean mismatch) implements Verdict {}

        record Pending(String why) implements Verdict {}
    }

    private Mono<Result> executed(ActionProposal p, String confirmation) {
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
        log.info("reconciliation_corrected proposalId={} from={} to=EXECUTED signature={} confirmation={}", p.id(), p.status(), prev.signature(), confirmation);
        return proposals.commit(ProposalTransition.from(p, next)
                        .audit(audit.event(p.ownerUserId(), p.walletId(), p.id(), EV_RECONCILED, ACTOR,
                                        ProposalService.payload("from", p.status(), "to", ProposalStatus.EXECUTED, "signature", prev.signature(), "confirmation", confirmation)),
                                audit.event(p.ownerUserId(), p.walletId(), p.id(), ExecutionService.EV_EXECUTED, ACTOR,
                                        ProposalService.payload("signature", prev.signature(), "explorerUrl", prev.explorerUrl(), "confirmation", confirmation)))
                        .publish(ProposalService.tradeEvent(next, TradeEvents.CONFIRMED, now,
                                ProposalService.payload("signature", prev.signature(), "explorerUrl", prev.explorerUrl(), "confirmation", confirmation, "reconciled", true))))
                .thenReturn(Result.CORRECTED);
    }

    private Mono<Result> fail(ActionProposal p, String reason, String confirmation, boolean mismatch) {
        Instant now = clock.instant();
        ExecutionRecord prev = p.execution();
        ExecutionRecord rec = prev == null
                ? new ExecutionRecord(ExecutionRecord.FAILED, null, null, null, now, null, null, reason, null, null, 1, now)
                : prev.withStatus(ExecutionRecord.FAILED, null, confirmation, reason).withReconciliationAttempt(now);
        ActionProposal next = p.withExecution(rec, now).withStatus(ProposalStatus.FAILED, now);
        metrics.tradeFailed(prev != null && prev.hasSignature() ? CryptobotMetrics.FailureStage.ONCHAIN : CryptobotMetrics.FailureStage.OTHER, assetOf(p));
        metrics.tradeReconciled("corrected");
        log.warn("reconciliation_corrected proposalId={} from={} to=FAILED reason={}", p.id(), p.status(), reason);
        return proposals.commit(ProposalTransition.from(p, next)
                        .audit(audit.event(p.ownerUserId(), p.walletId(), p.id(), EV_RECONCILED, ACTOR,
                                        ProposalService.payload("from", p.status(), "to", ProposalStatus.FAILED, "reason", reason, "mismatch", mismatch)),
                                audit.event(p.ownerUserId(), p.walletId(), p.id(), ExecutionService.EV_FAILED, ACTOR, ProposalService.payload("error", reason)))
                        .publish(ProposalService.tradeEvent(next, TradeEvents.FAILED, now,
                                ProposalService.payload("error", reason, "stage", "reconciliation", "signature", rec.signature(), "mismatch", mismatch))))
                .thenReturn(Result.CORRECTED);
    }

    private Mono<Result> stillPending(ActionProposal p, String why) {
        Instant now = clock.instant();
        ExecutionRecord rec = p.execution().withReconciliationAttempt(now);
        if (rec.reconciliationAttempts() >= config.maxAttempts()) {
            return deadLetter(p, "No verdict after " + rec.reconciliationAttempts() + " reconciliation attempts (" + why + ")",
                    ProposalService.payload("signature", rec.signature(), "lastValidBlockHeight", rec.lastValidBlockHeight()));
        }
        metrics.tradeReconciled("matched");
        log.info("reconciliation_pending proposalId={} attempt={} why={}", p.id(), rec.reconciliationAttempts(), why);
        return proposals.commit(ProposalTransition.from(p, p.withExecution(rec, now))).thenReturn(Result.MATCHED);
    }

    /**
     * Ambiguous: the row is left in flight (it is the truth we have), the attempt counter is set
     * to the ceiling so the sweep skips it, and a dead letter carries the alert.
     */
    private Mono<Result> deadLetter(ActionProposal p, String reason, Map<String, Object> details) {
        Instant now = clock.instant();
        Map<String, Object> payload = new LinkedHashMap<>(details);
        payload.put("status", p.status().name());
        payload.put("walletId", p.walletId().toString());
        DeadLetter letter = DeadLetter.of(DeadLetter.Source.RECONCILIATION, p.id(), p.id(), p.ownerUserId(), reason, payload, now);
        log.error("reconciliation_ambiguous proposalId={} status={} reason={} — DLQ, human decision required", p.id(), p.status(), reason);
        ExecutionRecord prev = p.execution();
        ExecutionRecord rec = prev == null
                ? new ExecutionRecord(p.status() == ProposalStatus.SUBMITTED ? ExecutionRecord.SUBMITTED : ExecutionRecord.SIGNED, null, null, null, null, null, null, reason, null, null, config.maxAttempts(), now)
                : new ExecutionRecord(prev.status(), prev.signature(), prev.explorerUrl(), prev.signerPublicKey(), prev.submittedAt(), prev.confirmedAt(),
                        prev.confirmationStatus(), reason, prev.recentBlockhash(), prev.lastValidBlockHeight(), config.maxAttempts(), now);
        return deadLetters.append(letter)
                .then(proposals.commit(ProposalTransition.from(p, p.withExecution(rec, now))
                        .audit(audit.event(p.ownerUserId(), p.walletId(), p.id(), EV_AMBIGUOUS, ACTOR, ProposalService.payload("reason", reason, "deadLetterId", letter.id())))))
                .then(deadLetters.countUnresolved().doOnNext(metrics::dlqSize))
                .thenReturn(Result.DEAD_LETTERED);
    }

    private static String assetOf(ActionProposal p) {
        return ProposalService.assetOf(p.plan());
    }
}
