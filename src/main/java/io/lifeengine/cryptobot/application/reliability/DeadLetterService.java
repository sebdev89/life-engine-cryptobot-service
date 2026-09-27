package io.lifeengine.cryptobot.application.reliability;

import io.lifeengine.cryptobot.application.controlplane.AuditService;
import io.lifeengine.cryptobot.application.controlplane.ControlPlaneExceptions;
import io.lifeengine.cryptobot.application.controlplane.ProposalService;
import io.lifeengine.cryptobot.core.reliability.DeadLetter;
import io.lifeengine.cryptobot.core.reliability.OutboxEvent;
import io.lifeengine.cryptobot.core.execution.ActionProposal;
import io.lifeengine.cryptobot.core.execution.ExecutionRecord;
import io.lifeengine.cryptobot.core.execution.ProposalTransition;
import io.lifeengine.cryptobot.infrastructure.persistence.controlplane.ActionProposalRepository;
import io.lifeengine.cryptobot.infrastructure.persistence.controlplane.DeadLetterRepository;
import io.lifeengine.cryptobot.infrastructure.persistence.controlplane.OutboxRepository;
import io.lifeengine.cryptobot.observability.CryptobotMetrics;
import io.lifeengine.cryptobot.observability.ErrorCode;
import io.lifeengine.cryptobot.observability.LogFields;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * KAN-571 / KAN-501 (CB-11): the way out of the dead-letter queue. Until now a letter was written
 * and counted ({@code dlq_size}) but nothing ever resolved it. Two human actions, both audited,
 * both at most once per letter:
 *
 * <ul>
 *   <li><b>resolve</b> — "I looked; close it". For a {@code RECONCILIATION} letter the proposal is
 *       first <em>settled</em> against the chain ({@link ReconciliationService#settle}): what the
 *       chain proves wins ({@code EXECUTED} / {@code FAILED}); a signature it never saw and can no
 *       longer include is closed as {@code FAILED} with the human's note; no verdict yet ⇒ 409, the
 *       letter stays open (wait, or requeue). Never a state the chain contradicts, never a retry.
 *   <li><b>requeue</b> — "let the system try again". An {@code OUTBOX} letter puts its event back
 *       to {@code PENDING} (attempts reset, due now). A {@code RECONCILIATION} letter resets the
 *       proposal's attempt counter and reconciles it immediately: confirmed ⇒ closed; blockhash
 *       expired unseen ⇒ the idempotent retry under the <em>same</em> {@code operationId}; no
 *       verdict ⇒ back in the sweep. A second requeue of the same letter is a 409, and the retry
 *       itself is guarded by the proposal's version — there is no path to a second transaction.
 * </ul>
 */
@Service
public class DeadLetterService {

    private static final Logger log = LoggerFactory.getLogger(DeadLetterService.class);
    public static final String EV_RESOLVED = "DEAD_LETTER_RESOLVED";
    public static final String EV_REQUEUED = "DEAD_LETTER_REQUEUED";

    /** What a resolve/requeue left behind: the letter, and the proposal / outbox event it acted on (either may be null). */
    public record Resolution(DeadLetter deadLetter, ActionProposal proposal, ReconciliationService.Result reconciliation, OutboxEvent event) {}

    private final DeadLetterRepository deadLetters;
    private final ActionProposalRepository proposals;
    private final OutboxRepository outbox;
    private final ReconciliationService reconciliation;
    private final AuditService audit;
    private final CryptobotMetrics metrics;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public DeadLetterService(DeadLetterRepository deadLetters, ActionProposalRepository proposals, OutboxRepository outbox,
            ReconciliationService reconciliation, AuditService audit, CryptobotMetrics metrics) {
        this(deadLetters, proposals, outbox, reconciliation, audit, metrics, Clock.systemUTC());
    }

    public DeadLetterService(DeadLetterRepository deadLetters, ActionProposalRepository proposals, OutboxRepository outbox,
            ReconciliationService reconciliation, AuditService audit, CryptobotMetrics metrics, Clock clock) {
        this.deadLetters = deadLetters;
        this.proposals = proposals;
        this.outbox = outbox;
        this.reconciliation = reconciliation;
        this.audit = audit;
        this.metrics = metrics;
        this.clock = clock;
    }

    public Flux<DeadLetter> list(Boolean resolved, DeadLetter.Source source, UUID proposalId, int limit, int offset) {
        return deadLetters.findAll(resolved, source, proposalId, limit, offset);
    }

    public Mono<Long> open() {
        return deadLetters.countUnresolved();
    }

    public Mono<DeadLetter> require(UUID id) {
        return deadLetters.findById(id).switchIfEmpty(Mono.error(new ControlPlaneExceptions.NotFound("Dead letter " + id)));
    }

    public Mono<Resolution> resolve(UUID id, String actor, String note) {
        return require(id).flatMap(letter -> {
            if (letter.resolved()) {
                return Mono.error(new ControlPlaneExceptions.Conflict("Dead letter " + id + " was already resolved by " + letter.resolvedBy() + " at " + letter.resolvedAt()
                        + " (" + letter.outcome() + ")"));
            }
            Mono<Settled> settled = letter.source() == DeadLetter.Source.RECONCILIATION && letter.proposalId() != null
                    ? settle(letter, actor, note)
                    : Mono.just(new Settled(null, null));
            return settled.flatMap(s -> close(letter, actor, note, DeadLetter.Outcome.RESOLVED, s.proposal())
                    .flatMap(closed -> record(closed, EV_RESOLVED, actor, note, s.proposal(), s.result())
                            .thenReturn(new Resolution(closed, s.proposal(), s.result(), null))));
        });
    }

    public Mono<Resolution> requeue(UUID id, String actor, String note) {
        return require(id).flatMap(letter -> {
            if (letter.resolved()) {
                return Mono.error(new ControlPlaneExceptions.Conflict("Dead letter " + id + " was already resolved by " + letter.resolvedBy() + " at " + letter.resolvedAt()
                        + " (" + letter.outcome() + "); nothing to requeue"));
            }
            if (letter.source() == DeadLetter.Source.OUTBOX) {
                return outbox.requeue(letter.refId(), clock.instant())
                        .switchIfEmpty(Mono.error(new ControlPlaneExceptions.Conflict("Outbox event " + letter.refId() + " is not FAILED; nothing to requeue")))
                        .flatMap(event -> close(letter, actor, note, DeadLetter.Outcome.REQUEUED, null)
                                .flatMap(closed -> record(closed, EV_REQUEUED, actor, note, null, null)
                                        .thenReturn(new Resolution(closed, null, null, event))));
            }
            return proposal(letter).flatMap(p -> {
                ExecutionRecord exec = p.execution();
                if (!p.status().inFlight()) {
                    return Mono.error(new ControlPlaneExceptions.Conflict("Proposal " + p.id() + " is already " + p.status() + "; resolve the letter instead"));
                }
                if (exec == null || !exec.hasSignature()) {
                    return Mono.error(new ControlPlaneExceptions.Conflict("Proposal " + p.id() + " has no signature to reconcile; resolve the letter instead"));
                }
                Instant now = clock.instant();
                // 1. Give the row back to the reconciler (attempts reset) — audited in the same commit.
                ActionProposal reset = p.withExecution(exec.withReconciliationAttempts(0, null, now), now);
                return proposals.commit(ProposalTransition.from(p, reset)
                                .audit(audit.event(p.ownerUserId(), p.walletId(), p.id(), EV_REQUEUED, actor,
                                        ProposalService.payload("deadLetterId", letter.id(), "kind", letter.payload().get("kind"), "note", note,
                                                "signature", exec.signature(), "retries", exec.retries(), "operationId", p.operationId()))))
                        // 2. Close the letter — once. A concurrent requeue loses here, before any chain action.
                        .flatMap(fresh -> close(letter, actor, note, DeadLetter.Outcome.REQUEUED, fresh)
                                // 3. Reconcile now: confirmed ⇒ closed; expired unseen ⇒ idempotent retry; no verdict ⇒ the sweep continues.
                                .flatMap(closed -> reconciliation.reconcile(fresh)
                                        .onErrorResume(ex -> {
                                            log.warn("dead_letter_requeue_reconcile_failed deadLetterId={} proposalId={} error={}", letter.id(), p.id(), ex.toString(),
                                                    LogFields.event("dead_letter_requeue"), LogFields.status("failed"), ErrorCode.DLQ_REQUEUE_FAILED.kv());
                                            return Mono.just(ReconciliationService.Result.SKIPPED);
                                        })
                                        .flatMap(result -> proposals.findByIdAndOwner(p.id(), p.ownerUserId()).defaultIfEmpty(fresh)
                                                .map(after -> new Resolution(closed, after, result, null)))));
            });
        });
    }

    private record Settled(ActionProposal proposal, ReconciliationService.Result result) {}

    /** The chain's verdict, applied; no verdict ⇒ 409 and the letter stays open. */
    private Mono<Settled> settle(DeadLetter letter, String actor, String note) {
        return proposal(letter).flatMap(p -> {
            if (!p.status().inFlight()) {
                return Mono.just(new Settled(p, ReconciliationService.Result.SKIPPED)); // a live request or the sweep already closed it
            }
            return reconciliation.settle(p, actor, note).flatMap(result -> {
                if (result == ReconciliationService.Result.SKIPPED) {
                    return Mono.error(new ControlPlaneExceptions.Conflict("Proposal " + p.id() + " has no verdict from the chain yet (signature "
                            + (p.execution() == null ? null : p.execution().signature()) + " inside its blockhash window, or the RPC is unavailable);"
                            + " wait for the sweep, or requeue"));
                }
                return proposals.findByIdAndOwner(p.id(), p.ownerUserId()).defaultIfEmpty(p).map(after -> new Settled(after, result));
            });
        });
    }

    private Mono<ActionProposal> proposal(DeadLetter letter) {
        if (letter.proposalId() == null || letter.ownerUserId() == null) {
            return Mono.error(new ControlPlaneExceptions.Conflict("Dead letter " + letter.id() + " is not bound to a proposal"));
        }
        return proposals.findByIdAndOwner(letter.proposalId(), letter.ownerUserId())
                .switchIfEmpty(Mono.error(new ControlPlaneExceptions.NotFound("Proposal " + letter.proposalId() + " of dead letter " + letter.id())));
    }

    private Mono<DeadLetter> close(DeadLetter letter, String actor, String note, DeadLetter.Outcome outcome, ActionProposal proposal) {
        return deadLetters.resolve(letter.id(), clock.instant(), actor, note, outcome)
                .switchIfEmpty(Mono.error(new ControlPlaneExceptions.Conflict("Dead letter " + letter.id() + " was resolved concurrently")))
                .doOnNext(closed -> {
                    metrics.deadLetter(outcome.name());
                    log.info("dead_letter_{} deadLetterId={} source={} proposalId={} by={}", outcome.name().toLowerCase(java.util.Locale.ROOT), closed.id(), closed.source(),
                            closed.proposalId(), actor,
                            LogFields.event("dead_letter_" + outcome.name().toLowerCase(java.util.Locale.ROOT)), LogFields.status("closed"),
                            LogFields.proposalId(closed.proposalId()));
                })
                .flatMap(closed -> deadLetters.countUnresolved().doOnNext(metrics::dlqSize).thenReturn(closed));
    }

    /** The audit record of a human decision on a letter (the requeue of a proposal is audited inside its commit instead). */
    private Mono<Void> record(DeadLetter closed, String eventType, String actor, String note, ActionProposal proposal, ReconciliationService.Result result) {
        UUID owner = closed.ownerUserId() != null ? closed.ownerUserId() : proposal != null ? proposal.ownerUserId() : null;
        if (owner == null) {
            return Mono.empty(); // an outbox letter of an aggregate without owner: the letter row itself is the record
        }
        return audit.record(owner, proposal == null ? null : proposal.walletId(), closed.proposalId(), eventType, actor,
                ProposalService.payload("deadLetterId", closed.id(), "source", closed.source(), "kind", closed.payload().get("kind"), "reason", closed.reason(),
                        "note", note, "outcome", closed.outcome(), "proposalStatus", proposal == null ? null : proposal.status(), "reconciliation", result)).then();
    }
}
