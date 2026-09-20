package io.lifeengine.cryptobot.domain.transactions;

import java.util.Set;

/**
 * Lifecycle of an action proposal. Transitions are enforced in {@code ProposalService};
 * anything not listed in {@link #canTransitionTo} is a 409.
 *
 * <p>KAN-403: {@code EXECUTING} covers everything up to (and including) signing; {@code SUBMITTED}
 * means {@code sendTransaction} returned a signature. Both are <em>in flight</em>: a process that
 * dies in either state leaves a row the {@code ReconciliationService} resolves against the chain,
 * never a retry that could broadcast the same trade twice.
 */
public enum ProposalStatus {
    PROPOSED,
    SIMULATED,
    BLOCKED_BY_POLICY,
    AWAITING_APPROVAL,
    APPROVED,
    REJECTED,
    EXECUTING,
    SUBMITTED,
    EXECUTED,
    FAILED,
    EXPIRED;

    public boolean terminal() {
        return this == BLOCKED_BY_POLICY || this == REJECTED || this == EXECUTED || this == FAILED || this == EXPIRED;
    }

    /** Between the human's approval and the chain's answer: the states reconciliation looks at. */
    public boolean inFlight() {
        return this == EXECUTING || this == SUBMITTED;
    }

    public boolean canTransitionTo(ProposalStatus next) {
        return switch (this) {
            case PROPOSED -> Set.of(SIMULATED, FAILED).contains(next);
            case SIMULATED -> Set.of(AWAITING_APPROVAL, BLOCKED_BY_POLICY).contains(next);
            case AWAITING_APPROVAL -> Set.of(APPROVED, REJECTED, EXPIRED).contains(next);
            case APPROVED -> Set.of(EXECUTING, EXPIRED, REJECTED).contains(next);
            case EXECUTING -> Set.of(SUBMITTED, EXECUTED, FAILED).contains(next);
            // KAN-571: SUBMITTED → EXECUTING is the reconciler's idempotent retry — only once the
            // chain can no longer include the first signature (blockhash expired, never seen).
            case SUBMITTED -> Set.of(EXECUTING, EXECUTED, FAILED).contains(next);
            default -> false;
        };
    }
}
