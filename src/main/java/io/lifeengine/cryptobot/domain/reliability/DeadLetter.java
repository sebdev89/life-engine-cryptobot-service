package io.lifeengine.cryptobot.domain.reliability;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Something the system could not resolve by itself and refuses to guess about: an outbox event
 * whose retries are exhausted, or an in-flight trade the chain gives no verdict on. Never
 * discarded; a human resolves it. {@code dlq_size} counts the unresolved rows.
 */
public record DeadLetter(
        UUID id,
        Source source,
        UUID refId,
        UUID proposalId,
        UUID ownerUserId,
        String reason,
        Map<String, Object> payload,
        Instant createdAt,
        Instant resolvedAt) {

    public enum Source {
        OUTBOX,
        RECONCILIATION
    }

    public DeadLetter {
        payload = payload == null ? Map.of() : Map.copyOf(payload);
    }

    public static DeadLetter of(Source source, UUID refId, UUID proposalId, UUID ownerUserId, String reason, Map<String, Object> payload, Instant now) {
        return new DeadLetter(UUID.randomUUID(), source, refId, proposalId, ownerUserId, reason, payload, now, null);
    }
}
