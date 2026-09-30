package io.lifeengine.cryptobot.proofofvalue;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A ValueEvent as stored ({@code pov_value_event} + {@code pov_contribution}, V12). The anchor is
 * not here: it is read from the VALUE_EVENT receipt each time, so a RECORDED event shows ANCHORED
 * as soon as a sweep finalizes its batch.
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
        List<Contribution> contributions) {

    public ValueEventRecord {
        contributions = contributions == null ? List.of() : List.copyOf(contributions);
    }

    /** {@code displayName}/{@code kind} come from {@code pov_identity} on read; they are not written. */
    public record Contribution(int position, String identityId, ContributionRole role, int units, String displayName, IdentityKind kind) {}
}
