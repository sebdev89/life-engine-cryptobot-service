package io.lifeengine.cryptobot.infrastructure.persistence.controlplane;

import io.lifeengine.cryptobot.core.receipts.ReceiptAnchor;
import io.r2dbc.postgresql.codec.Json;
import io.r2dbc.spi.Row;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** {@code receipt_anchor} + {@code receipt_anchor_member} + the anchor columns of {@code intelligence_receipt} (V8). */
@Profile("!test")
@Component
public class AnchorR2dbcStore implements AnchorRepository {

    private static final String COLS = "root, chain, status, memo, receipt_count, fee_payer, tx, slot, blockhash, last_valid_block_height,"
            + " attempts, last_error, created_at, submitted_at, finalized_at, updated_at";

    private final DatabaseClient db;
    private final JsonDocs docs;
    private final TransactionalOperator tx;

    public AnchorR2dbcStore(DatabaseClient db, JsonDocs docs, TransactionalOperator tx) {
        this.db = db;
        this.docs = docs;
        this.tx = tx;
    }

    @Override
    public Flux<String> unanchoredReceiptHashes(int limit) {
        return db.sql("SELECT r.receipt_hash FROM intelligence_receipt r"
                        + " WHERE r.anchor_tx IS NULL AND NOT EXISTS (SELECT 1 FROM receipt_anchor_member m JOIN receipt_anchor a ON a.root = m.root"
                        + " WHERE m.receipt_hash = r.receipt_hash AND a.status <> 'ABANDONED')"
                        + " ORDER BY r.created_at ASC LIMIT :limit")
                .bind("limit", Math.max(1, limit))
                .map((row, meta) -> row.get("receipt_hash", String.class)).all();
    }

    @Override
    public Mono<Long> countUnanchored() {
        return db.sql("SELECT COUNT(*) AS n FROM intelligence_receipt WHERE anchor_tx IS NULL")
                .map((row, meta) -> row.get("n", Long.class)).one().defaultIfEmpty(0L);
    }

    @Override
    public Mono<ReceiptAnchor> insert(ReceiptAnchor a, List<ReceiptAnchor.Member> members) {
        Mono<Long> anchorRow = bindAll(db.sql("INSERT INTO receipt_anchor (" + COLS + ") VALUES (:root, :chain, :status, :memo, :count, :feePayer, :tx, :slot,"
                        + " :blockhash, :lastValid, :attempts, :lastError, :createdAt, :submittedAt, :finalizedAt, :updatedAt) ON CONFLICT (root) DO NOTHING"), a)
                .fetch().rowsUpdated();
        Mono<Void> memberRows = Flux.fromIterable(members).concatMap(m -> db.sql(
                        "INSERT INTO receipt_anchor_member (root, receipt_hash, proof) VALUES (:root, :hash, :proof) ON CONFLICT DO NOTHING")
                .bind("root", m.root()).bind("hash", m.receiptHash()).bind("proof", docs.write(m.proof()))
                .fetch().rowsUpdated()).then();
        return tx.transactional(anchorRow.flatMap(rows -> rows == 0 ? Mono.empty() : memberRows))
                .then(findByRoot(a.root()));
    }

    @Override
    public Mono<ReceiptAnchor> update(ReceiptAnchor a) {
        return bindAll(db.sql("UPDATE receipt_anchor SET chain = :chain, status = :status, memo = :memo, receipt_count = :count, fee_payer = :feePayer,"
                        + " tx = :tx, slot = :slot, blockhash = :blockhash, last_valid_block_height = :lastValid, attempts = :attempts, last_error = :lastError,"
                        + " created_at = :createdAt, submitted_at = :submittedAt, finalized_at = :finalizedAt, updated_at = :updatedAt WHERE root = :root"), a)
                .fetch().rowsUpdated()
                .then(findByRoot(a.root()));
    }

    private static DatabaseClient.GenericExecuteSpec bindAll(DatabaseClient.GenericExecuteSpec spec, ReceiptAnchor a) {
        spec = spec.bind("root", a.root()).bind("chain", a.chain()).bind("status", a.status().name()).bind("memo", a.memo())
                .bind("count", a.receiptCount()).bind("attempts", a.attempts()).bind("createdAt", a.createdAt()).bind("updatedAt", a.updatedAt());
        spec = bindNullable(spec, "feePayer", a.feePayer(), String.class);
        spec = bindNullable(spec, "tx", a.tx(), String.class);
        spec = bindNullable(spec, "slot", a.slot(), Long.class);
        spec = bindNullable(spec, "blockhash", a.blockhash(), String.class);
        spec = bindNullable(spec, "lastValid", a.lastValidBlockHeight(), Long.class);
        spec = bindNullable(spec, "lastError", a.lastError(), String.class);
        spec = bindNullable(spec, "submittedAt", a.submittedAt(), Instant.class);
        spec = bindNullable(spec, "finalizedAt", a.finalizedAt(), Instant.class);
        return spec;
    }

    private static <T> DatabaseClient.GenericExecuteSpec bindNullable(DatabaseClient.GenericExecuteSpec spec, String name, T value, Class<T> type) {
        return value == null ? spec.bindNull(name, type) : spec.bind(name, value);
    }

    @Override
    public Mono<ReceiptAnchor> findByRoot(String root) {
        return db.sql("SELECT " + COLS + " FROM receipt_anchor WHERE root = :root").bind("root", root).map((row, meta) -> read(row)).one();
    }

    @Override
    public Flux<ReceiptAnchor> findByStatus(ReceiptAnchor.Status status, int limit) {
        return db.sql("SELECT " + COLS + " FROM receipt_anchor WHERE status = :status ORDER BY updated_at ASC LIMIT :limit")
                .bind("status", status.name()).bind("limit", Math.max(1, limit)).map((row, meta) -> read(row)).all();
    }

    @Override
    public Flux<ReceiptAnchor> findRecent(int limit) {
        return db.sql("SELECT " + COLS + " FROM receipt_anchor ORDER BY created_at DESC LIMIT :limit")
                .bind("limit", Math.max(1, Math.min(limit, 200))).map((row, meta) -> read(row)).all();
    }

    @Override
    public Flux<ReceiptAnchor.Member> members(String root) {
        return db.sql("SELECT root, receipt_hash, proof FROM receipt_anchor_member WHERE root = :root ORDER BY receipt_hash")
                .bind("root", root).map((row, meta) -> member(row)).all();
    }

    @Override
    public Flux<ReceiptAnchor.Member> membersOwnedBy(String root, UUID ownerId) {
        return db.sql("SELECT m.root, m.receipt_hash, m.proof FROM receipt_anchor_member m JOIN intelligence_receipt r ON r.receipt_hash = m.receipt_hash"
                        + " WHERE m.root = :root AND r.owner_id = :owner ORDER BY m.receipt_hash")
                .bind("root", root).bind("owner", ownerId).map((row, meta) -> member(row)).all();
    }

    @Override
    public Mono<ReceiptAnchor.Member> membershipOf(String receiptHash) {
        return db.sql("SELECT m.root, m.receipt_hash, m.proof FROM receipt_anchor_member m JOIN receipt_anchor a ON a.root = m.root"
                        + " WHERE m.receipt_hash = :hash AND a.status <> 'ABANDONED'"
                        + " ORDER BY CASE a.status WHEN 'FINALIZED' THEN 0 ELSE 1 END, a.updated_at DESC LIMIT 1")
                .bind("hash", receiptHash).map((row, meta) -> member(row)).one();
    }

    @Override
    public Mono<Long> stampReceipts(ReceiptAnchor a) {
        if (a.status() != ReceiptAnchor.Status.FINALIZED || a.tx() == null) {
            return Mono.error(new IllegalStateException("Only a FINALIZED anchor stamps receipts: " + a.root() + " is " + a.status()));
        }
        DatabaseClient.GenericExecuteSpec spec = db.sql("UPDATE intelligence_receipt r SET anchor_chain = :chain, anchor_tx = :tx, anchor_slot = :slot,"
                        + " anchor_root = m.root, anchor_proof = m.proof FROM receipt_anchor_member m"
                        + " WHERE m.root = :root AND m.receipt_hash = r.receipt_hash")
                .bind("chain", a.chain()).bind("tx", a.tx()).bind("root", a.root());
        spec = bindNullable(spec, "slot", a.slot(), Long.class);
        return tx.transactional(spec.fetch().rowsUpdated());
    }

    private static ReceiptAnchor read(Row row) {
        return new ReceiptAnchor(
                row.get("root", String.class),
                row.get("chain", String.class),
                ReceiptAnchor.Status.valueOf(row.get("status", String.class)),
                row.get("memo", String.class),
                row.get("receipt_count", Integer.class),
                row.get("fee_payer", String.class),
                row.get("tx", String.class),
                row.get("slot", Long.class),
                row.get("blockhash", String.class),
                row.get("last_valid_block_height", Long.class),
                row.get("attempts", Integer.class),
                row.get("last_error", String.class),
                row.get("created_at", Instant.class),
                row.get("submitted_at", Instant.class),
                row.get("finalized_at", Instant.class),
                row.get("updated_at", Instant.class));
    }

    @SuppressWarnings("unchecked")
    private ReceiptAnchor.Member member(Row row) {
        Json proof = row.get("proof", Json.class);
        return new ReceiptAnchor.Member(row.get("root", String.class), row.get("receipt_hash", String.class),
                proof == null ? List.of() : docs.read(proof, List.class));
    }
}
