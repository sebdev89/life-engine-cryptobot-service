package io.lifeengine.cryptobot.domain.receipt;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * A signed, content-addressed record of one step of the pipeline (Endgame §6). {@code receiptHash}
 * is the id: it is the hash of the canonical body, so the id proves integrity, identical bodies
 * collapse into one row, and a parent cannot reference a child (the child's hash does not exist
 * when the parent is hashed).
 *
 * <p>{@code anchor} is <em>outside</em> the hash and the signature: it is filled in later, by the
 * anchoring batch, and can be re-filled after a reorg without touching the receipt.
 */
public record IntelligenceReceipt(
        String receiptHash,
        String domain,
        ReceiptBody body,
        String canonicalJson,
        Signature signature,
        Anchor anchor,
        Instant createdAt) {

    /** Ed25519 over {@link ReceiptCanonicalizer#signingMessage}; {@code keyId} names the service key for rotation. */
    public record Signature(String alg, String keyId, String signatureBase64) {}

    /** Where the receipt was anchored: chain, memo transaction, slot, Merkle root and the inclusion proof. */
    public record Anchor(String chain, String tx, Long slot, String root, List<String> proof) {
        public Anchor {
            proof = proof == null ? List.of() : List.copyOf(proof);
        }
    }

    public IntelligenceReceipt {
        receiptHash = Digests.requireHash("receiptHash", receiptHash);
        domain = domain == null ? ReceiptCanonicalizer.HASH_DOMAIN : domain;
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(canonicalJson, "canonicalJson");
        Objects.requireNonNull(signature, "signature");
        Objects.requireNonNull(createdAt, "createdAt");
    }

    public ReceiptKind kind() {
        return body.kind();
    }

    public List<String> parents() {
        return body.parents();
    }

    public IntelligenceReceipt withAnchor(Anchor a) {
        return new IntelligenceReceipt(receiptHash, domain, body, canonicalJson, signature, a, createdAt);
    }
}
