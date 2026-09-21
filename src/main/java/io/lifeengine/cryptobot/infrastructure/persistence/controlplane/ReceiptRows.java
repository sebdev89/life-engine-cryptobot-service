package io.lifeengine.cryptobot.infrastructure.persistence.controlplane;

import io.lifeengine.cryptobot.domain.receipt.IntelligenceReceipt;
import io.lifeengine.cryptobot.domain.receipt.ReceiptBody;
import io.r2dbc.postgresql.codec.Json;
import io.r2dbc.spi.Row;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * The one mapping from an {@code intelligence_receipt} row to {@link IntelligenceReceipt}, shared
 * by {@link ReceiptR2dbcStore} and {@link LineageR2dbcStore} so a lineage walk returns exactly what
 * a point lookup returns (KAN-393).
 */
final class ReceiptRows {

    /** Column list of {@code intelligence_receipt}, qualified with the alias {@code r}. */
    static final String COLS = "r.receipt_hash, r.tenant_id, r.owner_id, r.kind, r.schema_version, r.nonce, r.wallet_id, r.proposal_id, r.body, r.canonical,"
            + " r.signature, r.key_id, r.reproducibility, r.anchor_chain, r.anchor_tx, r.anchor_slot, r.anchor_root, r.anchor_proof, r.created_at";

    private ReceiptRows() {}

    @SuppressWarnings("unchecked")
    static IntelligenceReceipt read(Row row, JsonDocs docs) {
        Map<String, Object> body = docs.read(row.get("body", Json.class), Map.class);
        byte[] canonical = row.get("canonical", byte[].class);
        byte[] signature = row.get("signature", byte[].class);
        String anchorTx = row.get("anchor_tx", String.class);
        IntelligenceReceipt.Anchor anchor = anchorTx == null ? null : new IntelligenceReceipt.Anchor(
                row.get("anchor_chain", String.class), anchorTx, row.get("anchor_slot", Long.class), row.get("anchor_root", String.class),
                row.get("anchor_proof", Json.class) == null ? List.of() : docs.read(row.get("anchor_proof", Json.class), List.class));
        return new IntelligenceReceipt(
                row.get("receipt_hash", String.class),
                null,
                ReceiptBody.fromMap(body),
                new String(canonical, StandardCharsets.UTF_8),
                new IntelligenceReceipt.Signature("ed25519", row.get("key_id", String.class), Base64.getEncoder().encodeToString(signature)),
                anchor,
                row.get("created_at", Instant.class));
    }
}
