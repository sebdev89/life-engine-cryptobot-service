package io.lifeengine.cryptobot.core.execution;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ProposalStatusTest {

    @Test
    void theOnlyRoadToTheChainGoesThroughApproval() {
        assertThat(ProposalStatus.PROPOSED.canTransitionTo(ProposalStatus.EXECUTING)).isFalse();
        assertThat(ProposalStatus.SIMULATED.canTransitionTo(ProposalStatus.EXECUTING)).isFalse();
        assertThat(ProposalStatus.AWAITING_APPROVAL.canTransitionTo(ProposalStatus.EXECUTING)).isFalse();
        assertThat(ProposalStatus.AWAITING_APPROVAL.canTransitionTo(ProposalStatus.EXECUTED)).isFalse();
        assertThat(ProposalStatus.APPROVED.canTransitionTo(ProposalStatus.EXECUTING)).isTrue();
        assertThat(ProposalStatus.EXECUTING.canTransitionTo(ProposalStatus.EXECUTED)).isTrue();
    }

    @Test
    void submittedSitsBetweenExecutingAndTheChainsVerdict() {
        // EXECUTING → SUBMITTED → EXECUTED|FAILED; reconciliation may also close EXECUTING directly.
        assertThat(ProposalStatus.EXECUTING.canTransitionTo(ProposalStatus.SUBMITTED)).isTrue();
        assertThat(ProposalStatus.SUBMITTED.canTransitionTo(ProposalStatus.EXECUTED)).isTrue();
        assertThat(ProposalStatus.SUBMITTED.canTransitionTo(ProposalStatus.FAILED)).isTrue();
        assertThat(ProposalStatus.SUBMITTED.canTransitionTo(ProposalStatus.EXECUTING)).isTrue(); // an internal ticket: idempotent retry after blockhash expiry
        assertThat(ProposalStatus.APPROVED.canTransitionTo(ProposalStatus.SUBMITTED)).isFalse();
        assertThat(ProposalStatus.EXECUTING.inFlight()).isTrue();
        assertThat(ProposalStatus.SUBMITTED.inFlight()).isTrue();
        assertThat(ProposalStatus.SUBMITTED.terminal()).isFalse();
        for (ProposalStatus s : ProposalStatus.values()) {
            if (s != ProposalStatus.EXECUTING && s != ProposalStatus.SUBMITTED) {
                assertThat(s.inFlight()).as(s.name()).isFalse();
            }
        }
    }

    @Test
    void terminalStatesAreDeadEnds() {
        for (ProposalStatus s : ProposalStatus.values()) {
            if (s.terminal()) {
                for (ProposalStatus next : ProposalStatus.values()) {
                    assertThat(s.canTransitionTo(next)).as(s + " -> " + next).isFalse();
                }
            }
        }
        assertThat(ProposalStatus.REJECTED.terminal()).isTrue();
        assertThat(ProposalStatus.BLOCKED_BY_POLICY.terminal()).isTrue();
        assertThat(ProposalStatus.EXECUTED.terminal()).isTrue();
    }

    @Test
    void policyDecidesBetweenApprovalAndBlocked() {
        assertThat(ProposalStatus.SIMULATED.canTransitionTo(ProposalStatus.AWAITING_APPROVAL)).isTrue();
        assertThat(ProposalStatus.SIMULATED.canTransitionTo(ProposalStatus.BLOCKED_BY_POLICY)).isTrue();
        assertThat(ProposalStatus.PROPOSED.canTransitionTo(ProposalStatus.AWAITING_APPROVAL)).isFalse();
    }
}
