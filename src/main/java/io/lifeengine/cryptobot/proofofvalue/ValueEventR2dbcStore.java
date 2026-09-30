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
        Flux<Long> knowledge = Flux.range(0, e.knowledgeAssetIds().size()).concatMap(i -> db.sql("INSERT INTO pov_value_event_knowledge"
                        + " (value_event_id, tenant_id, asset_id, position) VALUES (:event, :tenant, :asset, :pos)")
                .bind("event", e.id())
                .bind("tenant", e.tenantId())
                .bind("asset", e.knowledgeAssetIds().get(i))
                .bind("pos", i)
                .fetch().rowsUpdated());
        Flux<Long> compute = Flux.fromIterable(e.computeReceipts()).concatMap(c -> db.sql("INSERT INTO pov_compute_receipt (id, value_event_id,"
                        + " tenant_id, position, provider_id, provider_wallet, node, model, input_tokens, output_tokens, gpu_millis, estimated_cost_micro_usd,"
                        + " created_at) VALUES (:id, :event, :tenant, :pos, :provider, :wallet, :node, :model, :in, :out, :gpu, :cost, :created)")
                .bind("id", c.id())
                .bind("event", e.id())
                .bind("tenant", e.tenantId())
                .bind("pos", c.position())
                .bind("provider", c.providerId())
                .bind("wallet", c.providerWallet())
                .bind("node", c.node())
                .bind("model", c.model())
                .bind("in", c.inputTokens())
                .bind("out", c.outputTokens())
                .bind("gpu", c.gpuMillis())
                .bind("cost", c.estimatedCostMicroUsd())
                .bind("created", e.createdAt())
                .fetch().rowsUpdated());
        return tx.transactional(event.thenMany(contributions).thenMany(knowledge).thenMany(compute).then())
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

    @Override
    public Flux<ValueEventRecord> findAll(String tenantId) {
        return db.sql("SELECT " + COLS + " FROM pov_value_event WHERE tenant_id = :tenant ORDER BY accepted_at, created_at, id")
                .bind("tenant", tenantId).map((row, meta) -> map(row)).all()
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
                .zipWith(knowledgeOf(e.id()))
                .zipWith(computeOf(e.id()))
                .map(t -> e.withAttribution(new ArrayList<>(t.getT1().getT1()), t.getT1().getT2(), t.getT2()));
    }

    private Mono<List<String>> knowledgeOf(UUID eventId) {
        return db.sql("SELECT asset_id FROM pov_value_event_knowledge WHERE value_event_id = :event ORDER BY position")
                .bind("event", eventId).map((row, meta) -> row.get("asset_id", String.class)).all().collectList();
    }

    private Mono<List<ValueEventRecord.ComputeReceipt>> computeOf(UUID eventId) {
        return db.sql("SELECT r.id, r.position, r.provider_id, r.provider_wallet, r.node, r.model, r.input_tokens, r.output_tokens, r.gpu_millis,"
                        + " r.estimated_cost_micro_usd, i.display_name FROM pov_compute_receipt r"
                        + " JOIN pov_identity i ON i.tenant_id = r.tenant_id AND i.id = r.provider_id"
                        + " WHERE r.value_event_id = :event ORDER BY r.position")
                .bind("event", eventId)
                .map((row, meta) -> new ValueEventRecord.ComputeReceipt(
                        row.get("id", UUID.class),
                        row.get("position", Integer.class),
                        row.get("provider_id", String.class),
                        row.get("provider_wallet", String.class),
                        row.get("node", String.class),
                        row.get("model", String.class),
                        row.get("input_tokens", Long.class),
                        row.get("output_tokens", Long.class),
                        row.get("gpu_millis", Long.class),
                        row.get("estimated_cost_micro_usd", Long.class),
                        row.get("display_name", String.class)))
                .all().collectList();
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
