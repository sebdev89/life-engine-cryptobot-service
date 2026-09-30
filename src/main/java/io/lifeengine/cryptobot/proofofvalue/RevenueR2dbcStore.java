package io.lifeengine.cryptobot.proofofvalue;

import java.time.Instant;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** (V15): {@code pov_revenue_event} + {@code pov_revenue_link} + the revenue rows of {@code pov_payout}. */
@Profile("!test")
@Component
public class RevenueR2dbcStore implements RevenueRepository {

    private static final String COLS = "id, tenant_id, project_id, source_kind, source_ref, simulated, amount_lamports, attribution_policy,"
            + " revenue_share_bps, protocol_fee_bps, contributor_pool_lamports, protocol_fee_lamports, retained_lamports, treasury_identity_id,"
            + " receipt_hash, status, created_at, updated_at";

    private final DatabaseClient db;
    private final TransactionalOperator tx;

    public RevenueR2dbcStore(DatabaseClient db, TransactionalOperator tx) {
        this.db = db;
        this.tx = tx;
    }

    @Override
    public Mono<PovRevenueEvent> insert(PovRevenueEvent e) {
        DatabaseClient.GenericExecuteSpec head = db.sql("INSERT INTO pov_revenue_event (" + COLS + ") VALUES (:id, :tenant, :project, :kind, :ref,"
                        + " :simulated, :amount, :policy, :shareBps, :feeBps, :pool, :fee, :retained, :treasury, :receipt, :status, :created, :updated)")
                .bind("id", e.id())
                .bind("tenant", e.tenantId())
                .bind("project", e.projectId())
                .bind("kind", e.sourceKind())
                .bind("ref", e.sourceRef())
                .bind("simulated", e.simulated())
                .bind("amount", e.amountLamports())
                .bind("policy", e.policy())
                .bind("shareBps", e.revenueShareBps())
                .bind("feeBps", e.protocolFeeBps())
                .bind("pool", e.contributorPoolLamports())
                .bind("fee", e.protocolFeeLamports())
                .bind("retained", e.retainedLamports())
                .bind("status", e.status())
                .bind("created", e.createdAt())
                .bind("updated", e.updatedAt());
        head = e.treasuryIdentityId() == null ? head.bindNull("treasury", String.class) : head.bind("treasury", e.treasuryIdentityId());
        head = e.receiptHash() == null ? head.bindNull("receipt", String.class) : head.bind("receipt", e.receiptHash());
        Flux<Long> links = Flux.fromIterable(e.links()).concatMap(l -> db.sql("INSERT INTO pov_revenue_link (revenue_event_id, value_event_id, position,"
                        + " share_lamports) VALUES (:rev, :event, :pos, :share)")
                .bind("rev", e.id()).bind("event", l.valueEventId()).bind("pos", l.position()).bind("share", l.shareLamports())
                .fetch().rowsUpdated());
        Flux<Long> payouts = Flux.fromIterable(e.payouts()).concatMap(p -> {
            DatabaseClient.GenericExecuteSpec s = db.sql("INSERT INTO pov_payout (id, revenue_event_id, tenant_id, position, identity_id, wallet, lamports,"
                            + " status, policy, created_at, updated_at) VALUES (:id, :rev, :tenant, :pos, :identity, :wallet, :lamports, :status, :policy,"
                            + " :created, :updated)")
                    .bind("id", p.id())
                    .bind("rev", e.id())
                    .bind("tenant", e.tenantId())
                    .bind("pos", p.position())
                    .bind("identity", p.identityId())
                    .bind("lamports", p.lamports())
                    .bind("status", p.status())
                    .bind("policy", p.policy())
                    .bind("created", p.createdAt())
                    .bind("updated", p.updatedAt());
            s = p.wallet() == null ? s.bindNull("wallet", String.class) : s.bind("wallet", p.wallet());
            return s.fetch().rowsUpdated();
        });
        return tx.transactional(head.fetch().rowsUpdated().thenMany(links).thenMany(payouts).then())
                .then(Mono.defer(() -> find(e.tenantId(), e.id())));
    }

    @Override
    public Mono<PovRevenueEvent> find(String tenantId, UUID id) {
        return joined(db.sql("SELECT " + COLS + " FROM pov_revenue_event WHERE tenant_id = :tenant AND id = :id").bind("tenant", tenantId).bind("id", id)
                .map((row, meta) -> head(row)).one());
    }

    @Override
    public Mono<PovRevenueEvent> findBySource(String tenantId, String sourceKind, String sourceRef) {
        return joined(db.sql("SELECT " + COLS + " FROM pov_revenue_event WHERE tenant_id = :tenant AND source_kind = :kind AND source_ref = :ref")
                .bind("tenant", tenantId).bind("kind", sourceKind).bind("ref", sourceRef).map((row, meta) -> head(row)).one());
    }

    @Override
    public Flux<PovRevenueEvent> findRecent(String tenantId, int limit) {
        return db.sql("SELECT " + COLS + " FROM pov_revenue_event WHERE tenant_id = :tenant ORDER BY created_at DESC, id LIMIT :limit")
                .bind("tenant", tenantId).bind("limit", limit).map((row, meta) -> head(row)).all()
                .concatMap(e -> joined(Mono.just(e)));
    }

    @Override
    public Flux<PovRevenueEvent> findByTreasury(String tenantId, String identityId) {
        return db.sql("SELECT " + COLS + " FROM pov_revenue_event WHERE tenant_id = :tenant AND treasury_identity_id = :identity"
                        + " ORDER BY created_at DESC, id")
                .bind("tenant", tenantId).bind("identity", identityId).map((row, meta) -> head(row)).all()
                .concatMap(e -> joined(Mono.just(e)));
    }

    @Override
    public Mono<Void> updateStatus(PovRevenueEvent e) {
        DatabaseClient.GenericExecuteSpec s = db.sql("UPDATE pov_revenue_event SET status = :status, receipt_hash = :receipt, updated_at = :updated"
                        + " WHERE id = :id AND tenant_id = :tenant")
                .bind("status", e.status())
                .bind("updated", e.updatedAt())
                .bind("id", e.id())
                .bind("tenant", e.tenantId());
        s = e.receiptHash() == null ? s.bindNull("receipt", String.class) : s.bind("receipt", e.receiptHash());
        return s.fetch().rowsUpdated().then();
    }

    @Override
    public Flux<Share> sharesOf(String tenantId, UUID valueEventId) {
        return db.sql("SELECT l.revenue_event_id, l.share_lamports FROM pov_revenue_link l JOIN pov_revenue_event r ON r.id = l.revenue_event_id"
                        + " WHERE r.tenant_id = :tenant AND l.value_event_id = :event ORDER BY r.created_at, r.id")
                .bind("tenant", tenantId).bind("event", valueEventId)
                .map((row, meta) -> new Share(row.get("revenue_event_id", UUID.class), row.get("share_lamports", Long.class))).all();
    }

    private Mono<PovRevenueEvent> joined(Mono<PovRevenueEvent> head) {
        return head.flatMap(e -> Mono.zip(
                db.sql("SELECT l.value_event_id, l.position, l.share_lamports, v.title FROM pov_revenue_link l JOIN pov_value_event v"
                                + " ON v.id = l.value_event_id WHERE l.revenue_event_id = :rev ORDER BY l.position")
                        .bind("rev", e.id())
                        .map((row, meta) -> new PovRevenueEvent.Link(row.get("value_event_id", UUID.class), row.get("position", Integer.class),
                                row.get("share_lamports", Long.class), row.get("title", String.class)))
                        .all().collectList(),
                db.sql("SELECT " + PayoutR2dbcStore.PCOLS + PayoutR2dbcStore.PFROM + " WHERE p.revenue_event_id = :rev ORDER BY p.position")
                        .bind("rev", e.id()).map((row, meta) -> PayoutR2dbcStore.payout(row)).all().collectList())
                .map(t -> new PovRevenueEvent(e.id(), e.tenantId(), e.projectId(), e.sourceKind(), e.sourceRef(), e.simulated(), e.amountLamports(),
                        e.policy(), e.revenueShareBps(), e.protocolFeeBps(), e.contributorPoolLamports(), e.protocolFeeLamports(), e.retainedLamports(),
                        e.treasuryIdentityId(), e.receiptHash(), e.status(), e.createdAt(), e.updatedAt(), t.getT1(), t.getT2())));
    }

    private static PovRevenueEvent head(io.r2dbc.spi.Row row) {
        return new PovRevenueEvent(
                row.get("id", UUID.class),
                row.get("tenant_id", String.class),
                row.get("project_id", String.class),
                row.get("source_kind", String.class),
                row.get("source_ref", String.class),
                Boolean.TRUE.equals(row.get("simulated", Boolean.class)),
                row.get("amount_lamports", Long.class),
                row.get("attribution_policy", String.class),
                row.get("revenue_share_bps", Integer.class),
                row.get("protocol_fee_bps", Integer.class),
                row.get("contributor_pool_lamports", Long.class),
                row.get("protocol_fee_lamports", Long.class),
                row.get("retained_lamports", Long.class),
                row.get("treasury_identity_id", String.class),
                row.get("receipt_hash", String.class),
                row.get("status", String.class),
                row.get("created_at", Instant.class),
                row.get("updated_at", Instant.class),
                null,
                null);
    }
}
