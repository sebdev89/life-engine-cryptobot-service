package io.lifeengine.cryptobot.domain.transactions;

import java.util.Set;

/**
 * Lifecycle of an action proposal. Transitions are enforced in {@code ProposalService};
 * anything not listed in {@link #canTransitionTo} is a 409.
 */
public enum ProposalStatus {
    PROPOSED,
    SIMULATED,
    BLOCKED_BY_POLICY,
    AWAITING_APPROVAL,
    APPROVED,
    REJECTED,
    EXECUTING,
    EXECUTED,
    FAILED,
    EXPIRED;

    public boolean terminal() {
        return this == BLOCKED_BY_POLICY || this == REJECTED || this == EXECUTED || this == FAILED || this == EXPIRED;
    }

    public boolean canTransitionTo(ProposalStatus next) {
        return switch (this) {
            case PROPOSED -> Set.of(SIMULATED, FAILED).contains(next);
            case SIMULATED -> Set.of(AWAITING_APPROVAL, BLOCKED_BY_POLICY).contains(next);
            case AWAITING_APPROVAL -> Set.of(APPROVED, REJECTED, EXPIRED).contains(next);
            case APPROVED -> Set.of(EXECUTING, EXPIRED, REJECTED).contains(next);
            case EXECUTING -> Set.of(EXECUTED, FAILED).contains(next);
            default -> false;
        };
    }
}
