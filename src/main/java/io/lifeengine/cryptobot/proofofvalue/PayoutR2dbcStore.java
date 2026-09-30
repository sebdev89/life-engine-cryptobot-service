package io.lifeengine.cryptobot.proofofvalue;

import java.time.Instant;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** KAN-822 (V14): {@code pov_distribution} + {@code pov_payout}. */
@Profile("!test")
@Component
public class PayoutR2dbcStore implements PayoutRepository {

    private static final String DCOLS = "id, tenant_id, value_event_id, pool_lamports, policy, receipt_hash, status, created_at, updated_at";
    static final String PCOLS = "p.id, p.distribution_id, p.value_event_id, p.tenant_id, p.position, p.identity_id, i.display_name, p.wallet,"
            + " p.lamports, p.status, p.tx_signature, p.explorer_url, p.error, p.policy, p.created_at, p.updated_at, p.revenue_event_id";
    static final String PFROM = " FROM pov_payout p JOIN pov_identity i ON i.tenant_id = p.tenant_id AND i.id = p.identity_id";

    private final DatabaseClient db;
    private final TransactionalOperator tx;

    public PayoutR2dbcStore(DatabaseClient db, TransactionalOperator tx) {
        this.db = db;
        this.tx = tx;
    }

    @Override
    public Mono<PovDistribution> insert(PovDistribution d) {
        DatabaseClient.GenericExecuteSpec head = db.sql("INSERT INTO pov_distribution (" + DCOLS + ") VALUES (:id, :tenant, :event, :pool, :policy,"
                        + " :receipt, :status, :created, :updated)")
                .bind("id", d.id())
                .bind("tenant", d.tenantId())
                .bind("event", d.valueEventId())
                .bind("pool", d.poolLamports())
                .bind("policy", d.policy())
                .bind("status", d.status())
                .bind("created", d.createdAt())
                .bind("updated", d.updatedAt());
        head = d.receiptHash() == null ? head.bindNull("receipt", String.class) : head.bind("receipt", d.receiptHash());
        Flux<Long> payouts = Flux.fromIterable(d.payouts()).concatMap(p -> {
            DatabaseClient.GenericExecuteSpec s = db.sql("INSERT INTO pov_payout (id, distribution_id, value_event_id, tenant_id, position, identity_id,"
                            + " wallet, lamports, status, policy, created_at, updated_at) VALUES (:id, :dist, :event, :tenant, :pos, :identity, :wallet,"
                            + " :lamports, :status, :policy, :created, :updated)")
                    .bind("id", p.id())
                    .bind("dist", d.id())
                    .bind("event", d.valueEventId())
                    .bind("tenant", d.tenantId())
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
        return tx.transactional(head.fetch().rowsUpdated().thenMany(payouts).then())
                .then(Mono.defer(() -> findByEvent(d.tenantId(), d.valueEventId())));
    }

    @Override
    public Mono<PovDistribution> findByEvent(String tenantId, UUID valueEventId) {
        return db.sql("SELECT " + DCOLS + " FROM pov_distribution WHERE tenant_id = :tenant AND value_event_id = :event")
                .bind("tenant", tenantId).bind("event", valueEventId)
                .map((row, meta) -> new PovDistribution(
                        row.get("id", UUID.class),
                        row.get("tenant_id", String.class),
                        row.get("value_event_id", UUID.class),
                        row.get("pool_lamports", Long.class),
                        row.get("policy", String.class),
                        row.get("receipt_hash", String.class),
                        row.get("status", String.class),
                        row.get("created_at", Instant.class),
                        row.get("updated_at", Instant.class),
                        null))
                .one()
                .flatMap(d -> db.sql("SELECT " + PCOLS + PFROM + " WHERE p.distribution_id = :dist ORDER BY p.position")
                        .bind("dist", d.id()).map((row, meta) -> payout(row)).all().collectList().map(d::withPayouts));
    }

    @Override
    public Mono<Void> updatePayout(PovPayout p) {
        DatabaseClient.GenericExecuteSpec s = db.sql("UPDATE pov_payout SET status = :status, tx_signature = :tx, explorer_url = :explorer, error = :error,"
                        + " updated_at = :updated WHERE id = :id AND tenant_id = :tenant")
                .bind("status", p.status())
                .bind("updated", p.updatedAt())
                .bind("id", p.id())
                .bind("tenant", p.tenantId());
        s = p.txSignature() == null ? s.bindNull("tx", String.class) : s.bind("tx", p.txSignature());
        s = p.explorerUrl() == null ? s.bindNull("explorer", String.class) : s.bind("explorer", p.explorerUrl());
        s = p.error() == null ? s.bindNull("error", String.class) : s.bind("error", p.error());
        return s.fetch().rowsUpdated().then();
    }

    @Override
    public Mono<Void> updateDistribution(PovDistribution d) {
        DatabaseClient.GenericExecuteSpec s = db.sql("UPDATE pov_distribution SET status = :status, receipt_hash = :receipt, updated_at = :updated"
                        + " WHERE id = :id AND tenant_id = :tenant")
                .bind("status", d.status())
                .bind("updated", d.updatedAt())
                .bind("id", d.id())
                .bind("tenant", d.tenantId());
        s = d.receiptHash() == null ? s.bindNull("receipt", String.class) : s.bind("receipt", d.receiptHash());
        return s.fetch().rowsUpdated().then();
    }

    @Override
    public Flux<PovPayout> findByIdentity(String tenantId, String identityId) {
        return db.sql("SELECT " + PCOLS + PFROM + " WHERE p.tenant_id = :tenant AND p.identity_id = :identity ORDER BY p.created_at DESC, p.id")
                .bind("tenant", tenantId).bind("identity", identityId).map((row, meta) -> payout(row)).all();
    }

    @Override
    public Mono<Long> lamportsSince(String tenantId, Instant since) {
        return db.sql("SELECT COALESCE(SUM(lamports), 0) AS total FROM pov_payout WHERE tenant_id = :tenant"
                        + " AND status IN ('SUBMITTED', 'CONFIRMED') AND updated_at >= :since")
                .bind("tenant", tenantId).bind("since", since)
                .map((row, meta) -> ((Number) row.get("total")).longValue()).one().defaultIfEmpty(0L);
    }

    @Override
    public Flux<PovPayout> findAll(String tenantId) {
        return db.sql("SELECT " + PCOLS + PFROM + " WHERE p.tenant_id = :tenant ORDER BY p.created_at DESC, p.id")
                .bind("tenant", tenantId).map((row, meta) -> payout(row)).all();
    }

    static PovPayout payout(io.r2dbc.spi.Row row) {
        return new PovPayout(
                row.get("id", UUID.class),
                row.get("distribution_id", UUID.class),
                row.get("value_event_id", UUID.class),
                row.get("tenant_id", String.class),
                row.get("position", Integer.class),
                row.get("identity_id", String.class),
                row.get("display_name", String.class),
                row.get("wallet", String.class),
                row.get("lamports", Long.class),
                row.get("status", String.class),
                row.get("tx_signature", String.class),
                row.get("explorer_url", String.class),
                row.get("error", String.class),
                row.get("policy", String.class),
                row.get("created_at", Instant.class),
                row.get("updated_at", Instant.class),
                row.get("revenue_event_id", UUID.class));
    }
}
