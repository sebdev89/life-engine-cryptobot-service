package io.lifeengine.cryptobot.infrastructure.persistence.controlplane;

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
public class ValueEventR2dbcStore implements ValueEventRepository {

    private static final String COLS = "value_event_hash, receipt_hash, tenant_id, owner_id, contributor_id, contributor_kind, agent_id,"
            + " contribution_type, evidence_hash, evidence_ref, acceptor_id, acceptance_method, acceptance_hash, canonical, occurred_at, created_at";

    private final DatabaseClient db;

    public ValueEventR2dbcStore(DatabaseClient db) {
        this.db = db;
    }

    @Override
    public Mono<ValueEventRepository.Row> insert(ValueEventRepository.Row r) {
        DatabaseClient.GenericExecuteSpec spec = db.sql("INSERT INTO value_event (" + COLS + ") VALUES (:hash, :receipt, :tenant, :owner, :contributor,"
                        + " :ckind, :agent, :ctype, :ehash, :eref, :acceptor, :method, :ahash, :canonical, :occurred, :created)"
                        + " ON CONFLICT (value_event_hash) DO NOTHING")
                .bind("hash", r.valueEventHash())
                .bind("receipt", r.receiptHash())
                .bind("tenant", r.tenantId())
                .bind("owner", r.ownerId())
                .bind("contributor", r.contributorId())
                .bind("ckind", r.contributorKind())
                .bind("ctype", r.contributionType())
                .bind("ehash", r.evidenceHash())
                .bind("eref", r.evidenceRef())
                .bind("acceptor", r.acceptorId())
                .bind("method", r.acceptanceMethod())
                .bind("ahash", r.acceptanceHash())
                .bind("canonical", r.canonical())
                .bind("occurred", r.occurredAt())
                .bind("created", r.createdAt());
        spec = r.agentId() == null ? spec.bindNull("agent", String.class) : spec.bind("agent", r.agentId());
        return spec.fetch().rowsUpdated()
                .then(db.sql("SELECT " + COLS + " FROM value_event WHERE value_event_hash = :hash")
                        .bind("hash", r.valueEventHash()).map((row, meta) -> map(row)).one());
    }

    @Override
    public Mono<ValueEventRepository.Row> findByHashAndOwner(String valueEventHash, UUID ownerId) {
        return db.sql("SELECT " + COLS + " FROM value_event WHERE value_event_hash = :hash AND owner_id = :owner")
                .bind("hash", valueEventHash).bind("owner", ownerId).map((row, meta) -> map(row)).one();
    }

    @Override
    public Flux<ValueEventRepository.Row> findByOwner(UUID ownerId, int limit) {
        return db.sql("SELECT " + COLS + " FROM value_event WHERE owner_id = :owner ORDER BY occurred_at DESC LIMIT :limit")
                .bind("owner", ownerId).bind("limit", Math.max(1, Math.min(limit, 500))).map((row, meta) -> map(row)).all();
    }

    @Override
    public Flux<ValueEventRepository.Row> findByEvidence(UUID ownerId, String evidenceHash) {
        return db.sql("SELECT " + COLS + " FROM value_event WHERE owner_id = :owner AND evidence_hash = :ehash ORDER BY occurred_at DESC")
                .bind("owner", ownerId).bind("ehash", evidenceHash).map((row, meta) -> map(row)).all();
    }

    private static ValueEventRepository.Row map(io.r2dbc.spi.Row row) {
        return new ValueEventRepository.Row(
                row.get("value_event_hash", String.class),
                row.get("receipt_hash", String.class),
                row.get("tenant_id", String.class),
                row.get("owner_id", UUID.class),
                row.get("contributor_id", String.class),
                row.get("contributor_kind", String.class),
                row.get("agent_id", String.class),
                row.get("contribution_type", String.class),
                row.get("evidence_hash", String.class),
                row.get("evidence_ref", String.class),
                row.get("acceptor_id", String.class),
                row.get("acceptance_method", String.class),
                row.get("acceptance_hash", String.class),
                row.get("canonical", String.class),
                row.get("occurred_at", Instant.class),
                row.get("created_at", Instant.class));
    }
}
