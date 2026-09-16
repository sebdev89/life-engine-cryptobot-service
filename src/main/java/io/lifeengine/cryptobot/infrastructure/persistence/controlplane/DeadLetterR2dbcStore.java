package io.lifeengine.cryptobot.infrastructure.persistence.controlplane;

import io.lifeengine.cryptobot.domain.reliability.DeadLetter;
import io.r2dbc.postgresql.codec.Json;
import io.r2dbc.spi.Row;
import java.time.Instant;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Profile("!test")
@Component
public class DeadLetterR2dbcStore implements DeadLetterRepository {

    private static final String COLS = "id, source, ref_id, proposal_id, owner_user_id, reason, payload, created_at, resolved_at";

    private final DatabaseClient db;
    private final JsonDocs docs;

    public DeadLetterR2dbcStore(DatabaseClient db, JsonDocs docs) {
        this.db = db;
        this.docs = docs;
    }

    static Mono<Long> insert(DatabaseClient db, JsonDocs docs, DeadLetter d) {
        DatabaseClient.GenericExecuteSpec spec = db.sql(
                        "INSERT INTO dead_letter (id, source, ref_id, proposal_id, owner_user_id, reason, payload, created_at, resolved_at)"
                                + " VALUES (:id, :source, :ref, :proposal, :owner, :reason, :payload, :created, NULL)")
                .bind("id", d.id())
                .bind("source", d.source().name())
                .bind("ref", d.refId())
                .bind("reason", d.reason())
                .bind("payload", docs.write(d.payload()))
                .bind("created", d.createdAt());
        spec = d.proposalId() == null ? spec.bindNull("proposal", UUID.class) : spec.bind("proposal", d.proposalId());
        spec = d.ownerUserId() == null ? spec.bindNull("owner", UUID.class) : spec.bind("owner", d.ownerUserId());
        return spec.fetch().rowsUpdated();
    }

    @Override
    public Mono<DeadLetter> append(DeadLetter letter) {
        return insert(db, docs, letter).thenReturn(letter);
    }

    @Override
    public Flux<DeadLetter> findByProposal(UUID proposalId) {
        return db.sql("SELECT " + COLS + " FROM dead_letter WHERE proposal_id = :proposal ORDER BY created_at ASC")
                .bind("proposal", proposalId)
                .map((row, meta) -> map(row))
                .all();
    }

    @Override
    public Mono<Long> countUnresolved() {
        return db.sql("SELECT COUNT(*) AS n FROM dead_letter WHERE resolved_at IS NULL")
                .map((row, meta) -> row.get("n", Long.class))
                .one()
                .defaultIfEmpty(0L);
    }

    @SuppressWarnings("unchecked")
    private DeadLetter map(Row row) {
        return new DeadLetter(
                row.get("id", UUID.class),
                DeadLetter.Source.valueOf(row.get("source", String.class)),
                row.get("ref_id", UUID.class),
                row.get("proposal_id", UUID.class),
                row.get("owner_user_id", UUID.class),
                row.get("reason", String.class),
                docs.read(row.get("payload", Json.class), java.util.Map.class),
                row.get("created_at", Instant.class),
                row.get("resolved_at", Instant.class));
    }
}
