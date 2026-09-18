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
 *
 * <p>KAN-403: {@code operationId} is the idempotency key of the execution — unique across all
 * proposals, persisted in the same transaction that moves the row to {@code EXECUTING}, i.e.
 * before anything is signed. {@code version} is the optimistic lock: every commit expects the
 * version it read and bumps it, so two writers (two clicks, the executor and the reconciler)
 * can never both move the same row.
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
        Instant updatedAt,
        UUID operationId,
        long version) {

    public ActionProposal withStatus(ProposalStatus next, Instant now) {
        if (!status.canTransitionTo(next)) {
            throw new IllegalStateException("Invalid transition " + status + " -> " + next + " for proposal " + id);
        }
        return new ActionProposal(
                id, walletId, ownerUserId, walletAddress, cluster, next, kind, title, reasoningSummary, requestedBy,
                intent, plan, riskBefore, riskAfter, policy, simulation, transaction, approval, execution,
                runtimeRunId, snapshotId, expiresAt, createdAt, now, operationId, version);
    }

    public ActionProposal withSimulation(SimulationOutcome sim, PreparedTransaction tx, Instant now) {
        return new ActionProposal(
                id, walletId, ownerUserId, walletAddress, cluster, status, kind, title, reasoningSummary, requestedBy,
                intent, plan, riskBefore, riskAfter, policy, sim, tx, approval, execution,
                runtimeRunId, snapshotId, expiresAt, createdAt, now, operationId, version);
    }

    public ActionProposal withPolicy(PolicyDecision decision, Instant now) {
        return new ActionProposal(
                id, walletId, ownerUserId, walletAddress, cluster, status, kind, title, reasoningSummary, requestedBy,
                intent, plan, riskBefore, riskAfter, decision, simulation, transaction, approval, execution,
                runtimeRunId, snapshotId, expiresAt, createdAt, now, operationId, version);
    }

    public ActionProposal withApproval(ApprovalRecord record, Instant now) {
        return new ActionProposal(
                id, walletId, ownerUserId, walletAddress, cluster, status, kind, title, reasoningSummary, requestedBy,
                intent, plan, riskBefore, riskAfter, policy, simulation, transaction, record, execution,
                runtimeRunId, snapshotId, expiresAt, createdAt, now, operationId, version);
    }

    public ActionProposal withExecution(ExecutionRecord record, Instant now) {
        return new ActionProposal(
                id, walletId, ownerUserId, walletAddress, cluster, status, kind, title, reasoningSummary, requestedBy,
                intent, plan, riskBefore, riskAfter, policy, simulation, transaction, approval, record,
                runtimeRunId, snapshotId, expiresAt, createdAt, now, operationId, version);
    }

    /** KAN-438: the timelock may end after the original TTL; the window is pushed so the lock can be honoured. */
    public ActionProposal withExpiresAt(Instant expires, Instant now) {
        return new ActionProposal(
                id, walletId, ownerUserId, walletAddress, cluster, status, kind, title, reasoningSummary, requestedBy,
                intent, plan, riskBefore, riskAfter, policy, simulation, transaction, approval, execution,
                runtimeRunId, snapshotId, expires, createdAt, now, operationId, version);
    }

    public ActionProposal withOperation(UUID operation, Instant now) {
        return new ActionProposal(
                id, walletId, ownerUserId, walletAddress, cluster, status, kind, title, reasoningSummary, requestedBy,
                intent, plan, riskBefore, riskAfter, policy, simulation, transaction, approval, execution,
                runtimeRunId, snapshotId, expiresAt, createdAt, now, operation, version);
    }

    /** The store sets this from the row; the JSON document is not the authority for the lock. */
    public ActionProposal withVersion(long v) {
        return new ActionProposal(
                id, walletId, ownerUserId, walletAddress, cluster, status, kind, title, reasoningSummary, requestedBy,
                intent, plan, riskBefore, riskAfter, policy, simulation, transaction, approval, execution,
                runtimeRunId, snapshotId, expiresAt, createdAt, updatedAt, operationId, v);
    }
}
