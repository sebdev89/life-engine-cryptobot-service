package io.lifeengine.cryptobot.proofofvalue;

import java.time.Instant;

/**
 * A contributor as stored ({@code pov_identity}, V12). {@code tenantId} is the JWT owner resolved
 * server-side, never a request field. {@code ownerId}/{@code operatorId} name other identities of
 * the same tenant (an agent answers to its owner).
 */
public record PovIdentity(
        String tenantId,
        String id,
        IdentityKind kind,
        String displayName,
        String wallet,
        String ownerId,
        String operatorId,
        Instant createdAt) {}
