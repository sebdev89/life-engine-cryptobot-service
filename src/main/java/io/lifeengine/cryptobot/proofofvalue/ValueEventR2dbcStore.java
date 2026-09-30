package io.lifeengine.cryptobot.proofofvalue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Profile("!test")
@Component
public class ValueEventR2dbcStore implements ValueEventRepository {

    private static final String COLS = "id, tenant_id, owner_id, receipt_hash, value_event_hash, project_id, task_id, title, artifact_hash,"
            + " acceptance_hash, accepted_at, distribution_policy, total_units, canonical, created_at";

    private final DatabaseClient db;
    private final TransactionalOperator tx;

    public ValueEventR2dbcStore(DatabaseClient db, TransactionalOperator tx) {
        this.db = db;
        this.tx = tx;
    }

    @Override
    public Mono<ValueEventRecord> insert(ValueEventRecord e) {
        Mono<Long> event = db.sql("INSERT INTO pov_value_event (" + COLS + ") VALUES (:id, :tenant, :owner, :receipt, :hash, :project, :task,"
                        + " :title, :ahash, :acchash, :accepted, :policy, :units, :canonical, :created)")
                .bind("id", e.id())
                .bind("tenant", e.tenantId())
                .bind("owner", e.ownerId())
                .bind("receipt", e.receiptHash())
                .bind("hash", e.valueEventHash())
                .bind("project", e.projectId())
                .bind("task", e.taskId())
                .bind("title", e.title())
                .bind("ahash", e.artifactHash())
                .bind("acchash", e.acceptanceHash())
                .bind("accepted", e.acceptedAt())
                .bind("policy", e.distributionPolicy())
                .bind("units", e.totalUnits())
                .bind("canonical", e.canonical())
                .bind("created", e.createdAt())
                .fetch().rowsUpdated();
        Flux<Long> contributions = Flux.fromIterable(e.contributions()).concatMap(c -> db.sql("INSERT INTO pov_contribution"
                        + " (id, value_event_id, tenant_id, identity_id, role, units, position) VALUES (:id, :event, :tenant, :identity, :role, :units, :pos)")
                .bind("id", UUID.randomUUID())
                .bind("event", e.id())
                .bind("tenant", e.tenantId())
                .bind("identity", c.identityId())
                .bind("role", c.role().name())
                .bind("units", c.units())
                .bind("pos", c.position())
                .fetch().rowsUpdated());
        return tx.transactional(event.thenMany(contributions).then())
                .then(Mono.defer(() -> find(e.tenantId(), e.id())));
    }

    @Override
    public Mono<ValueEventRecord> find(String tenantId, UUID id) {
        return db.sql("SELECT " + COLS + " FROM pov_value_event WHERE tenant_id = :tenant AND id = :id")
                .bind("tenant", tenantId).bind("id", id).map((row, meta) -> map(row)).one()
                .flatMap(this::withContributions);
    }

    @Override
    public Mono<ValueEventRecord> findByHash(String tenantId, String valueEventHash) {
        return db.sql("SELECT " + COLS + " FROM pov_value_event WHERE tenant_id = :tenant AND value_event_hash = :hash")
                .bind("tenant", tenantId).bind("hash", valueEventHash).map((row, meta) -> map(row)).one()
                .flatMap(this::withContributions);
    }

    @Override
    public Flux<ValueEventRecord> findRecent(String tenantId, int limit) {
        return db.sql("SELECT " + COLS + " FROM pov_value_event WHERE tenant_id = :tenant ORDER BY created_at DESC, id LIMIT :limit")
                .bind("tenant", tenantId).bind("limit", limit).map((row, meta) -> map(row)).all()
                .concatMap(this::withContributions);
    }

    private Mono<ValueEventRecord> withContributions(ValueEventRecord e) {
        return db.sql("SELECT c.position, c.identity_id, c.role, c.units, i.display_name, i.kind FROM pov_contribution c"
                        + " JOIN pov_identity i ON i.tenant_id = c.tenant_id AND i.id = c.identity_id"
                        + " WHERE c.value_event_id = :event ORDER BY c.position")
                .bind("event", e.id())
                .map((row, meta) -> new ValueEventRecord.Contribution(
                        row.get("position", Integer.class),
                        row.get("identity_id", String.class),
                        ContributionRole.valueOf(row.get("role", String.class)),
                        row.get("units", Integer.class),
                        row.get("display_name", String.class),
                        IdentityKind.valueOf(row.get("kind", String.class))))
                .all().collectList()
                .map(cs -> new ValueEventRecord(e.id(), e.tenantId(), e.ownerId(), e.receiptHash(), e.valueEventHash(), e.projectId(), e.taskId(),
                        e.title(), e.artifactHash(), e.acceptanceHash(), e.acceptedAt(), e.distributionPolicy(), e.totalUnits(), e.canonical(),
                        e.createdAt(), new ArrayList<>(cs)));
    }

    private static ValueEventRecord map(io.r2dbc.spi.Row row) {
        return new ValueEventRecord(
                row.get("id", UUID.class),
                row.get("tenant_id", String.class),
                row.get("owner_id", UUID.class),
                row.get("receipt_hash", String.class),
                row.get("value_event_hash", String.class),
                row.get("project_id", String.class),
                row.get("task_id", String.class),
                row.get("title", String.class),
                row.get("artifact_hash", String.class),
                row.get("acceptance_hash", String.class),
                row.get("accepted_at", Instant.class),
                row.get("distribution_policy", String.class),
                row.get("total_units", Integer.class),
                row.get("canonical", String.class),
                row.get("created_at", Instant.class),
                List.of());
    }
}
