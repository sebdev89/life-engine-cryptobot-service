package io.lifeengine.cryptobot.infrastructure.persistence.r2dbc;

import io.lifeengine.cryptobot.application.pilot.SwapOrder;
import io.lifeengine.cryptobot.application.pilot.SwapOrderRepository;
import io.r2dbc.spi.Row;
import java.time.Instant;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * (V17, KAN-1249): {@code swap_order} and {@code swap_order_audit}. Every state change is one conditional
 * {@code UPDATE … WHERE status = :from RETURNING …}: it happens exactly once or not at all, whatever runs concurrently.
 * An approval's {@code jti} is UNIQUE, so a token cannot approve twice even across restarts.
 */
@Profile("!test")
@Component
public class SwapOrderR2dbcStore implements SwapOrderRepository {

    private static final String COLS = "id, idempotency_key, created_by, status, direction, cluster, owner, in_amount, min_out, max_slippage_bps,"
            + " preview, preview_hash, approval_nonce, approved_by, approved_at, approval_jti, expires_at, tx_signature, error, created_at, updated_at";

    private final DatabaseClient db;

    public SwapOrderR2dbcStore(DatabaseClient db) {
        this.db = db;
    }

    @Override
    public Mono<SwapOrder> insert(SwapOrder o) {
        return db.sql("INSERT INTO swap_order (id, idempotency_key, created_by, status, direction, cluster, owner, in_amount, min_out,"
                        + " max_slippage_bps, preview, preview_hash, approval_nonce, expires_at, created_at, updated_at) VALUES (:id, :key, :by,"
                        + " :status, :direction, :cluster, :owner, :in, :min, :slip, :preview, :hash, :nonce, :expires, :created, :created)"
                        + " RETURNING " + COLS)
                .bind("id", o.id()).bind("key", o.idempotencyKey()).bind("by", o.createdBy()).bind("status", o.status().name())
                .bind("direction", o.direction().name()).bind("cluster", o.cluster()).bind("owner", o.owner()).bind("in", o.inAmount())
                .bind("min", o.minOut()).bind("slip", o.maxSlippageBps()).bind("preview", o.preview()).bind("hash", o.previewHash())
                .bind("nonce", o.approvalNonce()).bind("expires", o.expiresAt()).bind("created", o.createdAt())
                .map((row, meta) -> order(row)).one();
    }

    @Override
    public Mono<SwapOrder> find(UUID id) {
        return db.sql("SELECT " + COLS + " FROM swap_order WHERE id = :id").bind("id", id).map((row, meta) -> order(row)).one();
    }

    @Override
    public Mono<SwapOrder> findByIdempotencyKey(String key) {
        return db.sql("SELECT " + COLS + " FROM swap_order WHERE idempotency_key = :key").bind("key", key).map((row, meta) -> order(row)).one();
    }

    @Override
    public Mono<SwapOrder> approve(UUID id, UUID approver, Instant at, String jti, Instant expiresAt) {
        return db.sql("UPDATE swap_order SET status = 'APPROVED', approved_by = :by, approved_at = :at, approval_jti = :jti, expires_at = :exp,"
                        + " updated_at = NOW() WHERE id = :id AND status = 'PENDING_APPROVAL' RETURNING " + COLS)
                .bind("id", id).bind("by", approver).bind("at", at).bind("jti", jti).bind("exp", expiresAt)
                .map((row, meta) -> order(row)).one()
                // the jti was already spent on another approval: that is a refusal, not an error
                .onErrorResume(DataIntegrityViolationException.class, ex -> Mono.empty());
    }

    @Override
    public Mono<SwapOrder> transition(UUID id, SwapOrder.Status from, SwapOrder.Status to, String error) {
        DatabaseClient.GenericExecuteSpec spec = db.sql("UPDATE swap_order SET status = :to, error = COALESCE(:error, error), updated_at = NOW()"
                        + " WHERE id = :id AND status = :from RETURNING " + COLS)
                .bind("id", id).bind("from", from.name()).bind("to", to.name());
        return nullable(spec, "error", error, String.class).map((row, meta) -> order(row)).one();
    }

    @Override
    public Mono<Void> audit(UUID orderId, String event, UUID actor, String previewHash, String detail) {
        DatabaseClient.GenericExecuteSpec spec = db.sql("INSERT INTO swap_order_audit (order_id, event, actor, preview_hash, detail)"
                        + " VALUES (:order, :event, :actor, :hash, :detail)")
                .bind("order", orderId).bind("event", event);
        spec = nullable(spec, "actor", actor, UUID.class);
        spec = nullable(spec, "hash", previewHash, String.class);
        spec = nullable(spec, "detail", detail, String.class);
        return spec.fetch().rowsUpdated().then();
    }

    @Override
    public Flux<String> auditEvents(UUID orderId) {
        return db.sql("SELECT event FROM swap_order_audit WHERE order_id = :order ORDER BY id").bind("order", orderId)
                .map((row, meta) -> row.get("event", String.class)).all();
    }

    private static <T> DatabaseClient.GenericExecuteSpec nullable(DatabaseClient.GenericExecuteSpec spec, String name, T value, Class<T> type) {
        return value == null ? spec.bindNull(name, type) : spec.bind(name, value);
    }

    private static SwapOrder order(Row r) {
        return new SwapOrder(
                r.get("id", UUID.class),
                r.get("idempotency_key", String.class),
                r.get("created_by", UUID.class),
                SwapOrder.Status.valueOf(r.get("status", String.class)),
                SwapOrder.Direction.valueOf(r.get("direction", String.class)),
                r.get("cluster", String.class),
                r.get("owner", String.class),
                r.get("in_amount", Long.class),
                r.get("min_out", Long.class),
                r.get("max_slippage_bps", Integer.class),
                r.get("preview", String.class),
                r.get("preview_hash", String.class),
                r.get("approval_nonce", String.class),
                r.get("approved_by", UUID.class),
                r.get("approved_at", Instant.class),
                r.get("approval_jti", String.class),
                r.get("expires_at", Instant.class),
                r.get("tx_signature", String.class),
                r.get("error", String.class),
                r.get("created_at", Instant.class),
                r.get("updated_at", Instant.class));
    }
}
