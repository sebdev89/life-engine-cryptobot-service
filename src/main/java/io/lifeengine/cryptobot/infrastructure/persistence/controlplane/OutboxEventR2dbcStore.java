package io.lifeengine.cryptobot.infrastructure.persistence.controlplane;

import io.lifeengine.cryptobot.domain.reliability.OutboxEvent;
import io.r2dbc.postgresql.codec.Json;
import io.r2dbc.spi.Row;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.context.annotation.Profile;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Outbox rows. Inserts happen inside {@link ActionProposalR2dbcStore#commit}; {@link #processDue}
 * is the worker's transaction: {@code SELECT … FOR UPDATE SKIP LOCKED} keeps two publishers off
 * the same event, and the outcome is written before the lock is released.
 */
@Profile("!test")
@Component
public class OutboxEventR2dbcStore implements OutboxRepository {

    private static final String COLS = "id, aggregate_type, aggregate_id, owner_user_id, event_type, payload, status, attempts, next_attempt_at, last_error, created_at, published_at";

    private final DatabaseClient db;
    private final JsonDocs docs;
    private final TransactionalOperator tx;

    public OutboxEventR2dbcStore(DatabaseClient db, JsonDocs docs, TransactionalOperator tx) {
        this.db = db;
        this.docs = docs;
        this.tx = tx;
    }

    static Mono<Long> insert(DatabaseClient db, JsonDocs docs, OutboxEvent e) {
        return db.sql("INSERT INTO outbox_event (id, aggregate_type, aggregate_id, owner_user_id, event_type, payload, status, attempts, next_attempt_at, last_error, created_at, published_at)"
                        + " VALUES (:id, :aggType, :aggId, :owner, :type, :payload, :status, :attempts, :next, NULL, :created, NULL)")
                .bind("id", e.id())
                .bind("aggType", e.aggregateType())
                .bind("aggId", e.aggregateId())
                .bind("owner", e.ownerUserId())
                .bind("type", e.eventType())
                .bind("payload", docs.write(e.payload()))
                .bind("status", e.status().name())
                .bind("attempts", e.attempts())
                .bind("next", e.nextAttemptAt())
                .bind("created", e.createdAt())
                .fetch().rowsUpdated();
    }

    @Override
    public Mono<Long> processDue(Instant now, int batchSize, Function<OutboxEvent, Mono<Outcome>> work) {
        Flux<OutboxEvent> locked = db.sql("SELECT " + COLS + " FROM outbox_event WHERE status = 'PENDING' AND next_attempt_at <= :now"
                        + " ORDER BY next_attempt_at ASC, created_at ASC LIMIT :limit FOR UPDATE SKIP LOCKED")
                .bind("now", now)
                .bind("limit", Math.max(1, Math.min(batchSize, 500)))
                .map((row, meta) -> map(row))
                .all();
        Mono<Long> batch = locked.collectList()
                .flatMapMany(Flux::fromIterable)
                .concatMap(e -> work.apply(e).flatMap(outcome -> apply(e, outcome)))
                .count();
        return tx.transactional(batch);
    }

    private Mono<Long> apply(OutboxEvent e, Outcome outcome) {
        if (outcome instanceof Outcome.Published p) {
            OutboxEvent done = e.published(p.at());
            return db.sql("UPDATE outbox_event SET status = 'PUBLISHED', attempts = :attempts, last_error = NULL, published_at = :published WHERE id = :id")
                    .bind("attempts", done.attempts()).bind("published", done.publishedAt()).bind("id", e.id())
                    .fetch().rowsUpdated();
        }
        if (outcome instanceof Outcome.Retry r) {
            OutboxEvent next = e.retryLater(r.nextAttemptAt(), r.error());
            return db.sql("UPDATE outbox_event SET attempts = :attempts, next_attempt_at = :next, last_error = :error WHERE id = :id")
                    .bind("attempts", next.attempts()).bind("next", next.nextAttemptAt()).bind("error", truncate(next.lastError())).bind("id", e.id())
                    .fetch().rowsUpdated();
        }
        Outcome.Dead d = (Outcome.Dead) outcome;
        OutboxEvent dead = e.failed(d.error());
        return db.sql("UPDATE outbox_event SET status = 'FAILED', attempts = :attempts, last_error = :error WHERE id = :id")
                .bind("attempts", dead.attempts()).bind("error", truncate(dead.lastError())).bind("id", e.id())
                .fetch().rowsUpdated()
                .then(DeadLetterR2dbcStore.insert(db, docs, d.letter()));
    }

    @Override
    public Flux<OutboxEvent> findByAggregate(UUID aggregateId) {
        return db.sql("SELECT " + COLS + " FROM outbox_event WHERE aggregate_id = :agg ORDER BY created_at ASC")
                .bind("agg", aggregateId)
                .map((row, meta) -> map(row))
                .all();
    }

    @Override
    public Mono<Long> countByStatus(OutboxEvent.Status status) {
        return db.sql("SELECT COUNT(*) AS n FROM outbox_event WHERE status = :status")
                .bind("status", status.name())
                .map((row, meta) -> row.get("n", Long.class))
                .one()
                .defaultIfEmpty(0L);
    }

    private static String truncate(String s) {
        return s == null ? null : s.length() > 2000 ? s.substring(0, 2000) : s;
    }

    @SuppressWarnings("unchecked")
    private OutboxEvent map(Row row) {
        Integer attempts = row.get("attempts", Integer.class);
        return new OutboxEvent(
                row.get("id", UUID.class),
                row.get("aggregate_type", String.class),
                row.get("aggregate_id", UUID.class),
                row.get("owner_user_id", UUID.class),
                row.get("event_type", String.class),
                docs.read(row.get("payload", Json.class), java.util.Map.class),
                OutboxEvent.Status.valueOf(row.get("status", String.class)),
                attempts == null ? 0 : attempts,
                row.get("next_attempt_at", Instant.class),
                row.get("last_error", String.class),
                row.get("created_at", Instant.class),
                row.get("published_at", Instant.class));
    }
}
