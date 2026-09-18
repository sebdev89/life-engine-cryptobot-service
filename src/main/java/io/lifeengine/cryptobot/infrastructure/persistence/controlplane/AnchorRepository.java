package io.lifeengine.cryptobot.infrastructure.persistence.controlplane;

import io.lifeengine.cryptobot.domain.receipt.ReceiptAnchor;
import java.util.List;
import java.util.UUID;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * {@code receipt_anchor} + {@code receipt_anchor_member} and the one write the anchoring batch is
 * allowed on {@code intelligence_receipt}: stamping the anchor columns once a root is FINALIZED
 * (KAN-394). Separate from {@link ReceiptRepository} on purpose — receipts are append-only there;
 * this is the only path that touches a receipt after it was issued, and it never touches the
 * body, the canonical bytes or the signature.
 */
public interface AnchorRepository {

    /**
     * Receipts with no anchor yet that are not already covered by a live batch (PENDING, SUBMITTED
     * or FAILED — those are retried by root). Members of an ABANDONED batch are eligible again.
     * Oldest first, so a batch closes the oldest debt.
     */
    Flux<String> unanchoredReceiptHashes(int limit);

    /** Receipts still without a finalized anchor: the {@code anchor_pending} gauge. */
    Mono<Long> countUnanchored();

    /** Writes the anchor and its members (with proofs) in one transaction. Same root again ⇒ the stored row. */
    Mono<ReceiptAnchor> insert(ReceiptAnchor anchor, List<ReceiptAnchor.Member> members);

    Mono<ReceiptAnchor> update(ReceiptAnchor anchor);

    Mono<ReceiptAnchor> findByRoot(String root);

    /** Oldest update first — the batch that has waited longest is settled first. */
    Flux<ReceiptAnchor> findByStatus(ReceiptAnchor.Status status, int limit);

    /** Newest first. */
    Flux<ReceiptAnchor> findRecent(int limit);

    Flux<ReceiptAnchor.Member> members(String root);

    /** The members of {@code root} whose receipts belong to {@code ownerId}: what a caller may see of a batch. */
    Flux<ReceiptAnchor.Member> membersOwnedBy(String root, UUID ownerId);

    /** The live (non-ABANDONED) batch a receipt belongs to, if any; FINALIZED preferred. */
    Mono<ReceiptAnchor.Member> membershipOf(String receiptHash);

    /**
     * Writes {@code chain, tx, slot, root, proof} on every member receipt of a FINALIZED anchor.
     * Returns the number of receipts stamped. Idempotent: a re-anchor after a reorg overwrites the
     * same columns with the new transaction.
     */
    Mono<Long> stampReceipts(ReceiptAnchor anchor);
}
