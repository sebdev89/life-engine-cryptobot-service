package io.lifeengine.cryptobot.proofofvalue;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A ValueEvent as stored ({@code pov_value_event} + {@code pov_contribution}, V12; + {@code pov_value_event_knowledge}
 * and {@code pov_compute_receipt}, V13). The anchor is not here: it is read from the VALUE_EVENT receipt each time,
 * so a RECORDED event shows ANCHORED as soon as a sweep finalizes its batch.
 */
public record ValueEventRecord(
        UUID id,
        String tenantId,
        UUID ownerId,
        String receiptHash,
        String valueEventHash,
        String projectId,
        String taskId,
        String title,
        String artifactHash,
        String acceptanceHash,
        Instant acceptedAt,
        String distributionPolicy,
        int totalUnits,
        String canonical,
        Instant createdAt,
        List<Contribution> contributions,
        List<String> knowledgeAssetIds,
        List<ComputeReceipt> computeReceipts) {

    public ValueEventRecord {
        contributions = contributions == null ? List.of() : List.copyOf(contributions);
        knowledgeAssetIds = knowledgeAssetIds == null ? List.of() : List.copyOf(knowledgeAssetIds);
        computeReceipts = computeReceipts == null ? List.of() : List.copyOf(computeReceipts);
    }

    /** A V1 event: no knowledge assets, no compute receipts. */
    public ValueEventRecord(UUID id, String tenantId, UUID ownerId, String receiptHash, String valueEventHash, String projectId, String taskId,
            String title, String artifactHash, String acceptanceHash, Instant acceptedAt, String distributionPolicy, int totalUnits, String canonical,
            Instant createdAt, List<Contribution> contributions) {
        this(id, tenantId, ownerId, receiptHash, valueEventHash, projectId, taskId, title, artifactHash, acceptanceHash, acceptedAt, distributionPolicy,
                totalUnits, canonical, createdAt, contributions, List.of(), List.of());
    }

    public ValueEventRecord withAttribution(List<Contribution> cs, List<String> assetIds, List<ComputeReceipt> receipts) {
        return new ValueEventRecord(id, tenantId, ownerId, receiptHash, valueEventHash, projectId, taskId, title, artifactHash, acceptanceHash,
                acceptedAt, distributionPolicy, totalUnits, canonical, createdAt, cs, assetIds, receipts);
    }

    /** {@code displayName}/{@code kind} come from {@code pov_identity} on read; they are not written. */
    public record Contribution(int position, String identityId, ContributionRole role, int units, String displayName, IdentityKind kind) {}

    /**
     * What the outcome cost to compute (V4). {@code providerWallet} is the provider's wallet when it was recorded;
     * {@code providerDisplayName} comes from {@code pov_identity} on read. GPU time in milliseconds.
     */
    public record ComputeReceipt(UUID id, int position, String providerId, String providerWallet, String node, String model, long inputTokens,
            long outputTokens, long gpuMillis, long estimatedCostMicroUsd, String providerDisplayName) {}
}
