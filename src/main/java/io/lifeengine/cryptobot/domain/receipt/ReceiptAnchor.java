package io.lifeengine.cryptobot.domain.receipt;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * One anchoring batch: a Merkle root over a set of receipts, the memo that carries it, and the
 * devnet transaction that carries the memo. The root is the identity: re-anchoring after a dropped
 * or reorged transaction reuses the same row, the same memo and the same proofs — only the
 * transaction changes. That is what makes the re-anchor idempotent (Endgame §11).
 *
 * <pre>
 *   PENDING ──submit──▶ SUBMITTED ──finalized──▶ FINALIZED        (receipts stamped here, never before)
 *                          │  err · dropped (blockhash expired, never seen)
 *                          ▼
 *                        FAILED ──resubmit (attempts &lt; max)──▶ SUBMITTED
 *                          │  attempts ≥ max
 *                          ▼
 *                       ABANDONED   (its receipts become eligible for a new batch)
 * </pre>
 */
public record ReceiptAnchor(
        String root,
        String chain,
        Status status,
        String memo,
        int receiptCount,
        String feePayer,
        String tx,
        Long slot,
        String blockhash,
        Long lastValidBlockHeight,
        int attempts,
        String lastError,
        Instant createdAt,
        Instant submittedAt,
        Instant finalizedAt,
        Instant updatedAt) {

    public enum Status {
        PENDING,
        SUBMITTED,
        FINALIZED,
        FAILED,
        ABANDONED;

        /** The batch is over: its receipts either carry the anchor or go to a new batch. */
        public boolean terminal() {
            return this == FINALIZED || this == ABANDONED;
        }
    }

    /** One receipt of the batch with the siblings that lead from its leaf to the root. */
    public record Member(String root, String receiptHash, List<String> proof) {
        public Member {
            root = Digests.requireHash("root", root);
            receiptHash = Digests.requireHash("receiptHash", receiptHash);
            proof = proof == null ? List.of() : List.copyOf(proof);
        }
    }

    public ReceiptAnchor {
        root = Digests.requireHash("root", root);
        Objects.requireNonNull(chain, "chain");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(memo, "memo");
        Objects.requireNonNull(createdAt, "createdAt");
        updatedAt = updatedAt == null ? createdAt : updatedAt;
        if (receiptCount <= 0) {
            throw new IllegalArgumentException("receiptCount must be positive");
        }
    }

    public static ReceiptAnchor pending(String root, String chain, AnchorMemo memo, Instant now) {
        return new ReceiptAnchor(root, chain, Status.PENDING, memo.text(), memo.count(), null, null, null, null, null, 0, null, now, null, null, now);
    }

    public ReceiptAnchor submitted(String feePayer, String tx, String blockhash, long lastValidBlockHeight, Instant now) {
        return new ReceiptAnchor(root, chain, Status.SUBMITTED, memo, receiptCount, feePayer, tx, null, blockhash, lastValidBlockHeight,
                attempts + 1, null, createdAt, now, null, now);
    }

    public ReceiptAnchor finalized(long slot, Instant now) {
        return new ReceiptAnchor(root, chain, Status.FINALIZED, memo, receiptCount, feePayer, tx, slot, blockhash, lastValidBlockHeight,
                attempts, null, createdAt, submittedAt, now, now);
    }

    public ReceiptAnchor failed(String error, Instant now) {
        return new ReceiptAnchor(root, chain, Status.FAILED, memo, receiptCount, feePayer, tx, null, blockhash, lastValidBlockHeight,
                attempts, error, createdAt, submittedAt, null, now);
    }

    /** An attempt that died before a transaction was recorded (signer refused, RPC down): counts, keeps the previous tx out. */
    public ReceiptAnchor failedAttempt(String error, Instant now) {
        return new ReceiptAnchor(root, chain, Status.FAILED, memo, receiptCount, feePayer, null, null, null, null,
                attempts + 1, error, createdAt, submittedAt, null, now);
    }

    public ReceiptAnchor abandoned(String error, Instant now) {
        return new ReceiptAnchor(root, chain, Status.ABANDONED, memo, receiptCount, feePayer, tx, null, blockhash, lastValidBlockHeight,
                attempts, error, createdAt, submittedAt, null, now);
    }

    /** An ABANDONED batch whose exact receipt set came up again: same root, same memo, attempts start over. */
    public ReceiptAnchor reopened(Instant now) {
        return new ReceiptAnchor(root, chain, Status.PENDING, memo, receiptCount, feePayer, null, null, null, null, 0, null, createdAt, null, null, now);
    }

    /** What a receipt stores once the batch is FINALIZED (outside its hash and signature). */
    public IntelligenceReceipt.Anchor anchorFor(Member member) {
        return new IntelligenceReceipt.Anchor(chain, tx, slot, root, member.proof());
    }
}
