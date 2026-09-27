package io.lifeengine.cryptobot.infrastructure.persistence.controlplane;

import io.lifeengine.cryptobot.core.receipts.DeterministicInference;
import io.lifeengine.cryptobot.core.receipts.IntelligenceReceipt;
import io.lifeengine.cryptobot.core.receipts.ReceiptArtifact;
import io.lifeengine.cryptobot.core.receipts.ReceiptEdge;
import io.lifeengine.cryptobot.core.receipts.ReceiptKind;
import java.util.List;
import java.util.UUID;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Append-only store of the provenance DAG (KAN-391). There is no update path for a receipt's
 * body or signature; the only mutable columns are the anchor, written once by the anchoring batch.
 */
public interface ReceiptRepository {

    /**
     * Writes the receipt, its edges and its artifacts in one transaction.
     *
     * <ul>
     *   <li>Same hash again ⇒ no-op, the stored receipt is returned (content-addressed).
     *   <li>Same {@code (tenantId, nonce)} with a different hash ⇒ {@code Conflict}: a replay.
     *   <li>A parent that does not exist ⇒ {@code Conflict} (FK): the DAG never dangles.
     *   <li>{@code inference} (nullable) is written in the same transaction: an L1 receipt is never
     *       stored without the trees that make it re-executable.
     * </ul>
     */
    Mono<IntelligenceReceipt> insert(IntelligenceReceipt receipt, List<ReceiptEdge> edges, List<ReceiptArtifact> artifacts,
            DeterministicInference inference);

    Mono<IntelligenceReceipt> findByHash(String receiptHash);

    /** The stored input/output trees of an L1 receipt (KAN-392), if the issuing step kept them. */
    Mono<DeterministicInference> findInference(String receiptHash);

    /** Owner-scoped lookup: a receipt of another owner is a 404, not a 403. */
    Mono<IntelligenceReceipt> findByHashAndOwner(String receiptHash, UUID ownerId);

    /** Existing hashes among {@code hashes} that belong to {@code tenantId} — what a child may name as parent. */
    Flux<String> existingInTenant(List<String> hashes, String tenantId);

    Mono<IntelligenceReceipt> findByNonce(String tenantId, String nonce);

    /** Newest first. */
    Flux<IntelligenceReceipt> findByWallet(UUID walletId, int limit);

    /** Oldest first — the pipeline of one proposal in order. */
    Flux<IntelligenceReceipt> findByProposal(UUID proposalId);

    Mono<IntelligenceReceipt> findLatestByWalletAndKind(UUID walletId, ReceiptKind kind);

    Flux<ReceiptEdge> parentsOf(String childHash);

    Flux<ReceiptEdge> childrenOf(String parentHash);
}
