package io.lifeengine.cryptobot.infrastructure.persistence.controlplane;

import io.lifeengine.cryptobot.application.controlplane.ControlPlaneExceptions;
import io.lifeengine.cryptobot.core.receipts.DeterministicInference;
import io.lifeengine.cryptobot.core.receipts.IntelligenceReceipt;
import io.lifeengine.cryptobot.core.receipts.ReceiptArtifact;
import io.lifeengine.cryptobot.core.receipts.ReceiptBody;
import io.lifeengine.cryptobot.core.receipts.ReceiptEdge;
import io.lifeengine.cryptobot.core.receipts.ReceiptKind;
import io.r2dbc.postgresql.codec.Json;
import io.r2dbc.spi.R2dbcDataIntegrityViolationException;
import io.r2dbc.spi.Row;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * {@code intelligence_receipt} + {@code receipt_edge} + {@code artifact} + {@code deterministic_inference}. The receipt row stores
 * the canonical value tree as JSONB (what a lineage API returns) <em>and</em> the exact canonical
 * bytes that were hashed, so {@code verify} can check both that the bytes hash to the id and that
 * the JSON still canonicalises to those bytes.
 */
@Profile("!test")
@Component
public class ReceiptR2dbcStore implements ReceiptRepository {

    private static final String COLS = ReceiptRows.COLS;

    private final DatabaseClient db;
    private final JsonDocs docs;
    private final TransactionalOperator tx;

    public ReceiptR2dbcStore(DatabaseClient db, JsonDocs docs, TransactionalOperator tx) {
        this.db = db;
        this.docs = docs;
        this.tx = tx;
    }

    @Override
    public Mono<IntelligenceReceipt> insert(IntelligenceReceipt r, List<ReceiptEdge> edges, List<ReceiptArtifact> artifacts, DeterministicInference inference) {
        ReceiptBody b = r.body();
        DatabaseClient.GenericExecuteSpec spec = db.sql(
                        "INSERT INTO intelligence_receipt (receipt_hash, tenant_id, owner_id, kind, schema_version, nonce, wallet_id, proposal_id,"
                                + " body, canonical, signature, key_id, reproducibility, created_at)"
                                + " VALUES (:hash, :tenant, :owner, :kind, :schema, :nonce, :wallet, :proposal, :body, :canonical, :signature, :keyId, :level, :created)"
                                + " ON CONFLICT (receipt_hash) DO NOTHING")
                .bind("hash", r.receiptHash())
                .bind("tenant", b.tenantId())
                .bind("owner", UUID.fromString(b.ownerId()))
                .bind("kind", b.kind().name())
                .bind("schema", b.schemaVersion())
                .bind("nonce", b.nonce())
                .bind("body", docs.write(b.toMap()))
                .bind("canonical", r.canonicalJson().getBytes(StandardCharsets.UTF_8))
                .bind("signature", Base64.getDecoder().decode(r.signature().signatureBase64()))
                .bind("keyId", r.signature().keyId())
                .bind("level", b.reproducibility().name())
                .bind("created", r.createdAt());
        UUID walletId = b.refs() == null || b.refs().walletId() == null ? null : UUID.fromString(b.refs().walletId());
        UUID proposalId = b.refs() == null || b.refs().proposalId() == null ? null : UUID.fromString(b.refs().proposalId());
        spec = walletId == null ? spec.bindNull("wallet", UUID.class) : spec.bind("wallet", walletId);
        spec = proposalId == null ? spec.bindNull("proposal", UUID.class) : spec.bind("proposal", proposalId);

        Mono<Long> receipt = spec.fetch().rowsUpdated();
        Mono<Void> edgeRows = Flux.fromIterable(edges).concatMap(e -> db.sql(
                        "INSERT INTO receipt_edge (child_hash, parent_hash, role) VALUES (:child, :parent, :role) ON CONFLICT DO NOTHING")
                .bind("child", e.childHash()).bind("parent", e.parentHash()).bind("role", e.role().name())
                .fetch().rowsUpdated()).then();
        Mono<Void> artifactRows = Flux.fromIterable(artifacts).concatMap(a -> {
            DatabaseClient.GenericExecuteSpec s = db.sql(
                            "INSERT INTO artifact (artifact_hash, receipt_hash, tenant_id, type, schema, storage_ref)"
                                    + " VALUES (:hash, :receipt, :tenant, :type, :schema, :ref) ON CONFLICT (artifact_hash) DO NOTHING")
                    .bind("hash", a.artifactHash()).bind("receipt", a.receiptHash()).bind("tenant", a.tenantId()).bind("type", a.type());
            s = a.schema() == null ? s.bindNull("schema", String.class) : s.bind("schema", a.schema());
            s = a.storageRef() == null ? s.bindNull("ref", String.class) : s.bind("ref", a.storageRef());
            return s.fetch().rowsUpdated();
        }).then();

        Mono<Void> inferenceRow = inference == null ? Mono.empty() : Mono.defer(() -> db.sql(
                        "INSERT INTO deterministic_inference (receipt_hash, tenant_id, engine_id, engine_version, weights_hash, input, input_hash, output, output_hash)"
                                + " VALUES (:hash, :tenant, :engine, :version, :weights, :input, :inputHash, :output, :outputHash) ON CONFLICT (receipt_hash) DO NOTHING")
                .bind("hash", r.receiptHash()).bind("tenant", inference.tenantId()).bind("engine", inference.engineId()).bind("version", inference.engineVersion())
                .bind("weights", inference.weightsHash()).bind("input", docs.write(inference.input())).bind("inputHash", inference.inputHash())
                .bind("output", docs.write(inference.output())).bind("outputHash", inference.outputHash())
                .fetch().rowsUpdated().then());

        // rows == 0 ⇒ the hash already existed: content-addressed no-op, nothing else to write.
        return tx.transactional(receipt.flatMap(rows -> rows == 0 ? Mono.empty() : edgeRows.then(artifactRows).then(inferenceRow)))
                .onErrorMap(ReceiptR2dbcStore::isIntegrityViolation,
                        ex -> new ControlPlaneExceptions.Conflict("Receipt refused: nonce already used or a parent does not exist (" + r.receiptHash() + "): "
                                + (ex.getCause() != null ? ex.getCause().getMessage() : ex.getMessage())))
                .then(findByHash(r.receiptHash()));
    }

    private static boolean isIntegrityViolation(Throwable ex) {
        return ex instanceof DataIntegrityViolationException || ex instanceof R2dbcDataIntegrityViolationException
                || (ex.getCause() != null && ex.getCause() instanceof R2dbcDataIntegrityViolationException);
    }

    @Override
    public Mono<IntelligenceReceipt> findByHash(String receiptHash) {
        return db.sql("SELECT " + COLS + " FROM intelligence_receipt r WHERE receipt_hash = :hash")
                .bind("hash", receiptHash).map((row, meta) -> read(row)).one();
    }

    @Override
    @SuppressWarnings("unchecked")
    public Mono<DeterministicInference> findInference(String receiptHash) {
        return db.sql("SELECT receipt_hash, tenant_id, engine_id, engine_version, weights_hash, input, input_hash, output, output_hash"
                        + " FROM deterministic_inference WHERE receipt_hash = :hash")
                .bind("hash", receiptHash)
                .map((row, meta) -> new DeterministicInference(row.get("receipt_hash", String.class), row.get("tenant_id", String.class),
                        row.get("engine_id", String.class), row.get("engine_version", String.class), row.get("weights_hash", String.class),
                        docs.read(row.get("input", Json.class), Map.class), row.get("input_hash", String.class),
                        docs.read(row.get("output", Json.class), Map.class), row.get("output_hash", String.class)))
                .one();
    }

    @Override
    public Mono<IntelligenceReceipt> findByHashAndOwner(String receiptHash, UUID ownerId) {
        return db.sql("SELECT " + COLS + " FROM intelligence_receipt r WHERE receipt_hash = :hash AND owner_id = :owner")
                .bind("hash", receiptHash).bind("owner", ownerId).map((row, meta) -> read(row)).one();
    }

    @Override
    public Flux<String> existingInTenant(List<String> hashes, String tenantId) {
        if (hashes.isEmpty()) {
            return Flux.empty();
        }
        return db.sql("SELECT receipt_hash FROM intelligence_receipt WHERE tenant_id = :tenant AND receipt_hash IN (:hashes)")
                .bind("tenant", tenantId).bind("hashes", hashes)
                .map((row, meta) -> row.get("receipt_hash", String.class)).all();
    }

    @Override
    public Mono<IntelligenceReceipt> findByNonce(String tenantId, String nonce) {
        return db.sql("SELECT " + COLS + " FROM intelligence_receipt r WHERE tenant_id = :tenant AND nonce = :nonce")
                .bind("tenant", tenantId).bind("nonce", nonce).map((row, meta) -> read(row)).one();
    }

    @Override
    public Flux<IntelligenceReceipt> findByWallet(UUID walletId, int limit) {
        return db.sql("SELECT " + COLS + " FROM intelligence_receipt r WHERE wallet_id = :wallet ORDER BY created_at DESC LIMIT :limit")
                .bind("wallet", walletId).bind("limit", Math.max(1, Math.min(limit, 200))).map((row, meta) -> read(row)).all();
    }

    @Override
    public Flux<IntelligenceReceipt> findByProposal(UUID proposalId) {
        return db.sql("SELECT " + COLS + " FROM intelligence_receipt r WHERE proposal_id = :proposal ORDER BY created_at ASC")
                .bind("proposal", proposalId).map((row, meta) -> read(row)).all();
    }

    @Override
    public Mono<IntelligenceReceipt> findLatestByWalletAndKind(UUID walletId, ReceiptKind kind) {
        return db.sql("SELECT " + COLS + " FROM intelligence_receipt r WHERE wallet_id = :wallet AND kind = :kind ORDER BY created_at DESC LIMIT 1")
                .bind("wallet", walletId).bind("kind", kind.name()).map((row, meta) -> read(row)).one();
    }

    @Override
    public Flux<ReceiptEdge> parentsOf(String childHash) {
        return db.sql("SELECT child_hash, parent_hash, role FROM receipt_edge WHERE child_hash = :child ORDER BY parent_hash")
                .bind("child", childHash).map((row, meta) -> edge(row)).all();
    }

    @Override
    public Flux<ReceiptEdge> childrenOf(String parentHash) {
        return db.sql("SELECT child_hash, parent_hash, role FROM receipt_edge WHERE parent_hash = :parent ORDER BY child_hash")
                .bind("parent", parentHash).map((row, meta) -> edge(row)).all();
    }

    private static ReceiptEdge edge(Row row) {
        return new ReceiptEdge(row.get("child_hash", String.class), row.get("parent_hash", String.class),
                ReceiptEdge.Role.valueOf(row.get("role", String.class)));
    }

    private IntelligenceReceipt read(Row row) {
        return ReceiptRows.read(row, docs);
    }
}
