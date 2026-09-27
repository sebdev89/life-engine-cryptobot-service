package io.lifeengine.cryptobot.core.reliability;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Something the system could not resolve by itself and refuses to guess about: an outbox event
 * whose retries are exhausted, or an in-flight trade the chain gives no verdict on. Never
 * discarded; a human resolves it. {@code dlq_size} / {@code cryptobot_dead_letter_open} count the
 * unresolved rows.
 *
 * <p>KAN-571 / KAN-501: resolution is recorded on the row — {@code resolvedAt}, who
 * ({@code resolvedBy}), the note ({@code resolution}) and what was done ({@link Outcome}: closed by
 * hand, or requeued so the system tries again). A resolved letter is never resolved twice.
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
        Instant resolvedAt,
        String resolvedBy,
        String resolution,
        Outcome outcome) {

    public enum Source {
        OUTBOX,
        RECONCILIATION
    }

    /** What the human decided. */
    public enum Outcome {
        /** Closed by hand; the proposal (if any) was moved to the terminal state the chain proves, or left as the human found it. */
        RESOLVED,
        /** Given back to the system: the outbox event is PENDING again, or the reconciler may look at (and retry) the trade again. */
        REQUEUED
    }

    /** Pre-KAN-571 shape. */
    public DeadLetter(UUID id, Source source, UUID refId, UUID proposalId, UUID ownerUserId, String reason, Map<String, Object> payload, Instant createdAt, Instant resolvedAt) {
        this(id, source, refId, proposalId, ownerUserId, reason, payload, createdAt, resolvedAt, null, null, null);
    }

    public DeadLetter {
        payload = payload == null ? Map.of() : Map.copyOf(payload);
    }

    public static DeadLetter of(Source source, UUID refId, UUID proposalId, UUID ownerUserId, String reason, Map<String, Object> payload, Instant now) {
        return new DeadLetter(UUID.randomUUID(), source, refId, proposalId, ownerUserId, reason, payload, now, null, null, null, null);
    }

    public boolean resolved() {
        return resolvedAt != null;
    }

    public DeadLetter resolved(Instant at, String by, String note, Outcome outcome) {
        return new DeadLetter(id, source, refId, proposalId, ownerUserId, reason, payload, createdAt, at, by, note, outcome);
    }
}
