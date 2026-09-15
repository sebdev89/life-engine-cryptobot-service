package io.lifeengine.cryptobot.domain.transactions;

import io.lifeengine.cryptobot.domain.policy.PolicyDecision;
import io.lifeengine.cryptobot.domain.risk.RiskReport;
import io.lifeengine.cryptobot.domain.strategy.RebalanceIntent;
import io.lifeengine.cryptobot.domain.strategy.RebalancePlan;
import java.time.Instant;
import java.util.UUID;

/**
 * Everything the trace requires, in one aggregate: user, wallet, timestamp, reasoning, action,
 * asset/amount (in the plan), simulation, risk, policy, approval and execution signature.
 */
public record ActionProposal(
        UUID id,
        UUID walletId,
        UUID ownerUserId,
        String walletAddress,
        String cluster,
        ProposalStatus status,
        String kind,
        String title,
        String reasoningSummary,
        String requestedBy,
        RebalanceIntent intent,
        RebalancePlan plan,
        RiskReport riskBefore,
        RiskReport riskAfter,
        PolicyDecision policy,
        SimulationOutcome simulation,
        PreparedTransaction transaction,
        ApprovalRecord approval,
        ExecutionRecord execution,
        UUID runtimeRunId,
        UUID snapshotId,
        Instant expiresAt,
        Instant createdAt,
        Instant updatedAt) {

    public ActionProposal withStatus(ProposalStatus next, Instant now) {
        if (!status.canTransitionTo(next)) {
            throw new IllegalStateException("Invalid transition " + status + " -> " + next + " for proposal " + id);
        }
        return new ActionProposal(
                id, walletId, ownerUserId, walletAddress, cluster, next, kind, title, reasoningSummary, requestedBy,
                intent, plan, riskBefore, riskAfter, policy, simulation, transaction, approval, execution,
                runtimeRunId, snapshotId, expiresAt, createdAt, now);
    }

    public ActionProposal withSimulation(SimulationOutcome sim, PreparedTransaction tx, Instant now) {
        return new ActionProposal(
                id, walletId, ownerUserId, walletAddress, cluster, status, kind, title, reasoningSummary, requestedBy,
                intent, plan, riskBefore, riskAfter, policy, sim, tx, approval, execution,
                runtimeRunId, snapshotId, expiresAt, createdAt, now);
    }

    public ActionProposal withPolicy(PolicyDecision decision, Instant now) {
        return new ActionProposal(
                id, walletId, ownerUserId, walletAddress, cluster, status, kind, title, reasoningSummary, requestedBy,
                intent, plan, riskBefore, riskAfter, decision, simulation, transaction, approval, execution,
                runtimeRunId, snapshotId, expiresAt, createdAt, now);
    }

    public ActionProposal withApproval(ApprovalRecord record, Instant now) {
        return new ActionProposal(
                id, walletId, ownerUserId, walletAddress, cluster, status, kind, title, reasoningSummary, requestedBy,
                intent, plan, riskBefore, riskAfter, policy, simulation, transaction, record, execution,
                runtimeRunId, snapshotId, expiresAt, createdAt, now);
    }

    public ActionProposal withExecution(ExecutionRecord record, Instant now) {
        return new ActionProposal(
                id, walletId, ownerUserId, walletAddress, cluster, status, kind, title, reasoningSummary, requestedBy,
                intent, plan, riskBefore, riskAfter, policy, simulation, transaction, approval, record,
                runtimeRunId, snapshotId, expiresAt, createdAt, now);
    }
}
