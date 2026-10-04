package io.lifeengine.cryptobot.core.reliability;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * A business event written in the <em>same transaction</em> as the state change it describes
 * (transactional outbox). The row is the guarantee: if the state moved, the event exists;
 * if the process dies before anyone publishes it, the {@code OutboxPublisher} finds it on the next
 * tick. Nothing here is ever deleted — {@code PUBLISHED} rows are the durable event log a client
 * reads through {@code GET /api/cryptobot/proposals/{id}/events}.
 *
 * <p>There is no broker yet: the publisher hands events to an in-process
 * {@code OutboxSink}. Swapping the sink for NATS/JetStream does not touch this record.
 */
public record OutboxEvent(
        UUID id,
        String aggregateType,
        UUID aggregateId,
        UUID ownerUserId,
        String eventType,
        Map<String, Object> payload,
        Status status,
        int attempts,
        Instant nextAttemptAt,
        String lastError,
        Instant createdAt,
        Instant publishedAt) {

    public enum Status {
        PENDING,
        PUBLISHED,
        /** Retries exhausted; a {@link DeadLetter} points here. */
        FAILED
    }

    public static final String AGGREGATE_PROPOSAL = "action_proposal";

    public OutboxEvent {
        payload = payload == null ? Map.of() : Map.copyOf(payload);
    }

    public static OutboxEvent pending(String aggregateType, UUID aggregateId, UUID ownerUserId, String eventType, Map<String, Object> payload, Instant now) {
        return new OutboxEvent(UUID.randomUUID(), aggregateType, aggregateId, ownerUserId, eventType, payload, Status.PENDING, 0, now, null, now, null);
    }

    public OutboxEvent published(Instant at) {
        return new OutboxEvent(id, aggregateType, aggregateId, ownerUserId, eventType, payload, Status.PUBLISHED, attempts + 1, nextAttemptAt, null, createdAt, at);
    }

    public OutboxEvent retryLater(Instant next, String error) {
        return new OutboxEvent(id, aggregateType, aggregateId, ownerUserId, eventType, payload, Status.PENDING, attempts + 1, next, error, createdAt, null);
    }

    /** back to the publisher after a human requeue — PENDING, attempts reset, due now. */
    public OutboxEvent requeued(Instant now) {
        return new OutboxEvent(id, aggregateType, aggregateId, ownerUserId, eventType, payload, Status.PENDING, 0, now, null, createdAt, null);
    }

    public OutboxEvent failed(String error) {
        return new OutboxEvent(id, aggregateType, aggregateId, ownerUserId, eventType, payload, Status.FAILED, attempts + 1, nextAttemptAt, error, createdAt, null);
    }
}
