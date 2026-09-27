package io.lifeengine.cryptobot.core.execution;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Append-only trace row. {@code actor} is an email for humans, a service name for machines. */
public record AuditEvent(
        UUID id,
        UUID ownerUserId,
        UUID walletId,
        UUID proposalId,
        String eventType,
        String actor,
        Map<String, Object> payload,
        Instant createdAt) {

    public AuditEvent {
        payload = payload == null ? Map.of() : Map.copyOf(payload);
    }
}
