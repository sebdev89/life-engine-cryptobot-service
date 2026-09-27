package io.lifeengine.cryptobot.infrastructure.persistence.controlplane;

import io.lifeengine.cryptobot.core.execution.AuditEvent;
import io.r2dbc.postgresql.codec.Json;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Profile("!test")
@Component
public class AuditEventR2dbcStore implements AuditEventRepository {

    private final DatabaseClient db;
    private final JsonDocs docs;

    public AuditEventR2dbcStore(DatabaseClient db, JsonDocs docs) {
        this.db = db;
        this.docs = docs;
    }

    @Override
    public Mono<AuditEvent> append(AuditEvent e) {
        return insert(db, docs, e).thenReturn(e);
    }

    /** The INSERT alone, so {@link ActionProposalR2dbcStore#commit} can run it inside its transaction. */
    static Mono<Long> insert(DatabaseClient db, JsonDocs docs, AuditEvent e) {
        DatabaseClient.GenericExecuteSpec spec = db.sql(
                        "INSERT INTO audit_event (id, owner_user_id, wallet_id, proposal_id, event_type, actor, payload, created_at)"
                                + " VALUES (:id, :owner, :wallet, :proposal, :type, :actor, :payload, :created)")
                .bind("id", e.id())
                .bind("owner", e.ownerUserId())
                .bind("type", e.eventType())
                .bind("actor", e.actor())
                .bind("payload", docs.write(e.payload()))
                .bind("created", e.createdAt());
        spec = e.walletId() == null ? spec.bindNull("wallet", UUID.class) : spec.bind("wallet", e.walletId());
        spec = e.proposalId() == null ? spec.bindNull("proposal", UUID.class) : spec.bind("proposal", e.proposalId());
        return spec.fetch().rowsUpdated();
    }

    @Override
    public Flux<AuditEvent> findByProposal(UUID proposalId) {
        return db.sql("SELECT " + COLS + " FROM audit_event WHERE proposal_id = :proposal ORDER BY created_at ASC")
                .bind("proposal", proposalId)
                .map((row, meta) -> map(row))
                .all();
    }

    @Override
    public Flux<AuditEvent> findByWallet(UUID walletId, int limit) {
        return db.sql("SELECT " + COLS + " FROM audit_event WHERE wallet_id = :wallet ORDER BY created_at DESC LIMIT :limit")
                .bind("wallet", walletId)
                .bind("limit", Math.max(1, Math.min(limit, 200)))
                .map((row, meta) -> map(row))
                .all();
    }

    private static final String COLS = "id, owner_user_id, wallet_id, proposal_id, event_type, actor, payload, created_at";

    @SuppressWarnings("unchecked")
    private AuditEvent map(io.r2dbc.spi.Row row) {
        return new AuditEvent(
                row.get("id", UUID.class),
                row.get("owner_user_id", UUID.class),
                row.get("wallet_id", UUID.class),
                row.get("proposal_id", UUID.class),
                row.get("event_type", String.class),
                row.get("actor", String.class),
                docs.read(row.get("payload", Json.class), java.util.Map.class),
                row.get("created_at", java.time.Instant.class));
    }
}
