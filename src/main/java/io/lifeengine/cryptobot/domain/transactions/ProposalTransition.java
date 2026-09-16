package io.lifeengine.cryptobot.domain.transactions;

import io.lifeengine.cryptobot.domain.reliability.OutboxEvent;
import java.util.ArrayList;
import java.util.List;

/**
 * One atomic step of a proposal: the new state, what the row must still look like for the step to
 * apply ({@code expectedStatus} + {@code expectedVersion}, i.e. the snapshot the caller read), the
 * audit events that describe it and the outbox events it publishes. The repository commits all of
 * it in a single transaction or nothing (KAN-403 §31: "DB y eventos desincronizados → outbox").
 *
 * <p>A commit whose guard does not match (someone else moved the row first) fails with
 * {@code StaleProposal}; the caller re-reads and decides, never overwrites.
 */
public record ProposalTransition(
        ActionProposal proposal,
        ProposalStatus expectedStatus,
        long expectedVersion,
        List<AuditEvent> audit,
        List<OutboxEvent> outbox) {

    public ProposalTransition {
        audit = audit == null ? List.of() : List.copyOf(audit);
        outbox = outbox == null ? List.of() : List.copyOf(outbox);
    }

    /** Guarded by the state {@code before} was read in. */
    public static ProposalTransition from(ActionProposal before, ActionProposal after) {
        return new ProposalTransition(after, before.status(), before.version(), List.of(), List.of());
    }

    public ProposalTransition audit(AuditEvent... events) {
        List<AuditEvent> all = new ArrayList<>(audit);
        all.addAll(List.of(events));
        return new ProposalTransition(proposal, expectedStatus, expectedVersion, all, outbox);
    }

    public ProposalTransition publish(OutboxEvent... events) {
        List<OutboxEvent> all = new ArrayList<>(outbox);
        all.addAll(List.of(events));
        return new ProposalTransition(proposal, expectedStatus, expectedVersion, audit, all);
    }
}
