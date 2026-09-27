package io.lifeengine.cryptobot.application.receipt;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.lifeengine.cryptobot.solana.rpc.Base58;
import io.lifeengine.cryptobot.solana.rpc.SolanaCluster;
import io.lifeengine.cryptobot.solana.rpc.SolanaRpcClient;
import io.lifeengine.cryptobot.solana.rpc.SolanaRpcException;
import io.lifeengine.cryptobot.solana.tx.LegacyTransaction;
import io.lifeengine.cryptobot.solana.tx.MemoProgram;
import io.lifeengine.cryptobot.solana.tx.SolanaKeypair;
import io.lifeengine.cryptobot.application.controlplane.ControlPlaneExceptions;
import io.lifeengine.cryptobot.core.ports.AnchorPort;
import io.lifeengine.cryptobot.core.ports.ChainExecutionPort;
import io.lifeengine.cryptobot.core.receipts.AnchorMemo;
import io.lifeengine.cryptobot.core.receipts.Digests;
import io.lifeengine.cryptobot.core.receipts.IntelligenceReceipt;
import io.lifeengine.cryptobot.core.receipts.MerkleTree;
import io.lifeengine.cryptobot.core.receipts.ReceiptAnchor;
import io.lifeengine.cryptobot.infrastructure.persistence.controlplane.AnchorRepository;
import io.lifeengine.cryptobot.integration.signer.SignerClient;
import io.lifeengine.cryptobot.observability.CryptobotMetrics;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Anchors receipts on Solana devnet with a memo per batch (Endgame §11, KAN-394):
 *
 * <pre>
 *   unanchored receipts ─▶ Merkle tree (sorted leaves) ─▶ memo "ir/1 root=… n=… ts=…"
 *      ─▶ anchor + members (with proofs) persisted BEFORE anything is signed
 *      ─▶ isolated signer signs the memo tx (it re-derives the memo, devnet only)
 *      ─▶ sendTransaction ─▶ SUBMITTED ─▶ … finalized ─▶ FINALIZED ─▶ receipts stamped
 * </pre>
 *
 * <p>Rules that are not optional:
 *
 * <ul>
 *   <li><b>Finalized, not confirmed.</b> A receipt carries an anchor only once the memo's block is
 *       finalized. Before that a reorg can drop the transaction; the batch then goes FAILED and is
 *       re-sent for the <em>same root</em> — idempotent by root, the proofs do not change.
 *   <li><b>Devnet only.</b> {@link AnchorProperties} refuses any other cluster at startup and the
 *       signer refuses at signing time. Mainnet anchoring is a human decision with real money.
 *   <li><b>Nothing but hashes leaves the service.</b> The memo has a root, a count and a time.
 *   <li><b>The anchoring path never rewrites a receipt.</b> Only the anchor columns of
 *       {@code intelligence_receipt} are written, and only at FINALIZED.
 * </ul>
 */
@Service
public class AnchorService implements AnchorPort {

    private static final Logger log = LoggerFactory.getLogger(AnchorService.class);

    /** What one sweep did: batches whose state moved, and the batch opened for the receipts that were waiting. */
    public record SweepResult(List<ReceiptAnchor> settled, ReceiptAnchor anchored, long pending) {}

    /** A receipt's place in its batch, recomputed from the stored proof — what {@code verify} adds to a receipt (Endgame §15). */
    public record Inclusion(boolean anchored, String status, String chain, String tx, Long slot, String root, List<String> proof, Boolean proofValid, String explorerUrl) {
        static Inclusion none() {
            return new Inclusion(false, null, null, null, null, null, List.of(), null, null);
        }
    }

    /** The chain's side of a batch verification: was the memo transaction found finalized, and does it say what we say. */
    public record OnChain(boolean checked, boolean found, boolean failed, boolean memoMatches, Long slot, String error) {
        static OnChain skipped(String why) {
            return new OnChain(false, false, false, false, null, why);
        }
    }

    /**
     * {@code POST /anchors/{root}/verify}: the root recomputed from the members' receipt hashes,
     * every member's proof folded back to the root, the memo parsed against the root and count,
     * and the memo transaction read back from devnet at {@code finalized}.
     */
    public record AnchorVerification(
            String root,
            ReceiptAnchor.Status status,
            int receiptCount,
            int memberCount,
            String recomputedRoot,
            boolean rootMatches,
            boolean countMatches,
            boolean proofsValid,
            boolean memoMatches,
            String memo,
            String tx,
            Long slot,
            String explorerUrl,
            OnChain onChain) {

        @JsonProperty("valid")
        public boolean valid() {
            boolean local = rootMatches && countMatches && proofsValid && memoMatches;
            boolean chain = onChain == null || !onChain.checked() || (onChain.found() && !onChain.failed() && onChain.memoMatches());
            return local && chain;
        }
    }

    private final AnchorRepository anchors;
    private final SignerClient signer;
    private final SolanaRpcClient rpc;
    private final AnchorProperties props;
    private final CryptobotMetrics metrics;
    private final Clock clock;

    /** Test-friendly: no metrics exported, injectable clock. */
    public AnchorService(AnchorRepository anchors, SignerClient signer, SolanaRpcClient rpc, AnchorProperties props, Clock clock) {
        this(anchors, signer, rpc, props, CryptobotMetrics.noop(), clock);
    }

    @Autowired
    public AnchorService(AnchorRepository anchors, SignerClient signer, SolanaRpcClient rpc, AnchorProperties props, CryptobotMetrics metrics) {
        this(anchors, signer, rpc, props, metrics, Clock.systemUTC());
    }

    AnchorService(AnchorRepository anchors, SignerClient signer, SolanaRpcClient rpc, AnchorProperties props, CryptobotMetrics metrics, Clock clock) {
        this.anchors = anchors;
        this.signer = signer;
        this.rpc = rpc;
        this.props = props;
        this.metrics = metrics;
        this.clock = clock;
    }

    public AnchorProperties properties() {
        return props;
    }

    // ---- the sweep ----------------------------------------------------------------------------

    /**
     * One pass: settle what is in flight, retry what failed, open a batch for what is waiting.
     * With {@code waitForFinality} the new batch is polled until finalized (bounded by
     * {@code finality-wait}); otherwise the next sweep settles it.
     */
    public Mono<SweepResult> sweep(boolean waitForFinality) {
        return settle().collectList()
                .flatMap(settled -> anchorPending()
                        .flatMap(opt -> opt.map(a -> waitForFinality ? awaitFinality(a) : Mono.just(a)).orElse(Mono.empty()))
                        .map(Optional::of).defaultIfEmpty(Optional.empty())
                        .flatMap(opened -> anchors.countUnanchored().defaultIfEmpty(0L)
                                .doOnNext(metrics::anchorPending)
                                .map(pending -> new SweepResult(settled, opened.orElse(null), pending))));
    }

    /** Opens a batch for the receipts that have no live anchor, and submits it. Empty when nothing waits. */
    public Mono<Optional<ReceiptAnchor>> anchorPending() {
        return anchors.unanchoredReceiptHashes(props.batchSize()).collectList().flatMap(hashes -> {
            if (hashes.isEmpty()) {
                return Mono.just(Optional.<ReceiptAnchor>empty());
            }
            Instant now = clock.instant();
            MerkleTree tree = MerkleTree.of(hashes);
            AnchorMemo memo = new AnchorMemo(tree.root(), tree.size(), now);
            List<ReceiptAnchor.Member> members = new ArrayList<>(tree.size());
            for (String h : tree.leaves()) {
                members.add(new ReceiptAnchor.Member(tree.root(), h, tree.proofFor(h)));
            }
            ReceiptAnchor pending = ReceiptAnchor.pending(tree.root(), props.chain(), memo, now);
            log.info("anchor_batch_opened root={} receipts={} memo=\"{}\"", tree.root(), tree.size(), memo.text());
            return anchors.insert(pending, members)
                    // The same set of receipts hashes to the same root: an ABANDONED batch coming back starts its attempts over.
                    .flatMap(stored -> stored.status() == ReceiptAnchor.Status.ABANDONED ? anchors.update(stored.reopened(clock.instant())) : Mono.just(stored))
                    .flatMap(this::submit).map(Optional::of);
        });
    }

    /**
     * Builds, signs and broadcasts the memo transaction of a batch. The SUBMITTED row (with the
     * transaction id, which is the first signature and is known before broadcasting) is written
     * <em>before</em> {@code sendTransaction}, so a crash in between is settled by the next sweep
     * instead of re-sent blind. A refusal or an RPC rejection is a FAILED attempt.
     */
    public Mono<ReceiptAnchor> submit(ReceiptAnchor anchor) {
        SolanaCluster cluster = props.solanaCluster();
        return signer.identity()
                .flatMap(id -> id.map(Mono::just).orElseGet(() -> Mono.error(new SignerClient.SignerRefused("signer unavailable or disabled"))))
                .flatMap(identity -> {
                    if (!"devnet".equalsIgnoreCase(identity.cluster())) {
                        return Mono.error(new SignerClient.SignerRefused("signer is not on devnet: " + identity.cluster()));
                    }
                    String feePayer = identity.publicKey();
                    return rpc.getLatestBlockhash(cluster).flatMap(bh -> {
                        LegacyTransaction tx = new LegacyTransaction(feePayer, bh.blockhash(), List.of(MemoProgram.memo(anchor.memo())));
                        return signer.signAnchor(anchor.root(), anchor.receiptCount(), tx.unsignedBase64(), feePayer)
                                .map(resp -> new Signed(feePayer, resp.signedTransactionBase64(), verifySigned(tx, resp, feePayer), bh));
                    });
                })
                .flatMap(signed -> {
                    Instant now = clock.instant();
                    ReceiptAnchor submitted = anchor.submitted(signed.feePayer(), signed.signature(), signed.blockhash().blockhash(),
                            signed.blockhash().lastValidBlockHeight(), now);
                    return anchors.update(submitted)
                            .flatMap(stored -> rpc.sendTransaction(cluster, signed.signedBase64())
                                    .doOnNext(sig -> {
                                        if (!sig.equals(signed.signature())) {
                                            log.warn("anchor_signature_mismatch root={} expected={} returned={}", anchor.root(), signed.signature(), sig);
                                        }
                                        metrics.receiptAnchor("submitted");
                                        log.info("anchor_submitted root={} tx={} attempt={} explorer={}", stored.root(), sig, stored.attempts(),
                                                cluster.explorerTxUrl(sig));
                                    })
                                    .thenReturn(stored)
                                    .onErrorResume(ex -> broadcastFailed(stored, ex)));
                })
                .onErrorResume(ex -> {
                    // Nothing reached the chain: signer refused, RPC down, or the row could not be written.
                    Instant now = clock.instant();
                    log.warn("anchor_submit_failed root={} attempt={} error={}", anchor.root(), anchor.attempts() + 1, ex.toString());
                    metrics.receiptAnchor("failed");
                    return anchors.update(anchor.failedAttempt(ex.getMessage(), now));
                });
    }

    /**
     * KAN-596 ({@link AnchorPort}) — the same build-sign-broadcast steps as {@link #submit}, over
     * an explicit root/count instead of a persisted {@link ReceiptAnchor}. Devnet only (the signer
     * refuses anything else, same guard as {@link #submit}). Nothing is persisted here: {@link
     * #submit} keeps owning the batch bookkeeping ({@code ReceiptAnchor} rows, retries, {@link
     * #settle}) unchanged; this is the port's generic primitive for a caller that only wants "sign
     * and broadcast this root", not the sweep's write path.
     */
    @Override
    public Mono<ChainExecutionPort.Submission> anchor(String root, int count) {
        SolanaCluster cluster = props.solanaCluster();
        AnchorMemo memo = new AnchorMemo(root, count, clock.instant());
        return signer.identity()
                .flatMap(id -> id.map(Mono::just).orElseGet(() -> Mono.error(new SignerClient.SignerRefused("signer unavailable or disabled"))))
                .flatMap(identity -> {
                    if (!"devnet".equalsIgnoreCase(identity.cluster())) {
                        return Mono.error(new SignerClient.SignerRefused("signer is not on devnet: " + identity.cluster()));
                    }
                    String feePayer = identity.publicKey();
                    return rpc.getLatestBlockhash(cluster).flatMap(bh -> {
                        LegacyTransaction tx = new LegacyTransaction(feePayer, bh.blockhash(), List.of(MemoProgram.memo(memo.text())));
                        return signer.signAnchor(memo.root(), memo.count(), tx.unsignedBase64(), feePayer)
                                .map(resp -> new Signed(feePayer, resp.signedTransactionBase64(), verifySigned(tx, resp, feePayer), bh));
                    });
                })
                .flatMap(signed -> rpc.sendTransaction(cluster, signed.signedBase64())
                        .doOnNext(sig -> log.info("anchor_port_submitted root={} tx={} explorer={}", root, sig, cluster.explorerTxUrl(sig)))
                        .map(ChainExecutionPort.Submission::new));
    }

    private record Signed(String feePayer, String signedBase64, String signature, SolanaRpcClient.LatestBlockhash blockhash) {}

    /** Defence in depth (same as the trade path): the signer returned our message, signed by its own key. */
    private static String verifySigned(LegacyTransaction tx, SignerClient.SignResponse resp, String feePayer) {
        byte[] wire = Base64.getDecoder().decode(resp.signedTransactionBase64());
        byte[] message = tx.serializeMessage();
        if (wire.length != 1 + 64 + message.length || wire[0] != 1) {
            throw new ControlPlaneExceptions.Conflict("Signer returned an anchor transaction with an unexpected shape");
        }
        byte[] signature = Arrays.copyOfRange(wire, 1, 65);
        byte[] returned = Arrays.copyOfRange(wire, 65, wire.length);
        if (!Arrays.equals(message, returned)) {
            throw new ControlPlaneExceptions.Conflict("Signer altered the anchor transaction message");
        }
        if (!SolanaKeypair.verify(Base58.decode(feePayer), message, signature)) {
            throw new ControlPlaneExceptions.Conflict("Anchor signature does not verify against the signer public key");
        }
        return Base58.encode(signature);
    }

    /** A node-side rejection means nothing is on the chain: FAILED. Anything else is uncertain: the row stays SUBMITTED for {@link #settle}. */
    private Mono<ReceiptAnchor> broadcastFailed(ReceiptAnchor submitted, Throwable ex) {
        Instant now = clock.instant();
        if (ex instanceof SolanaRpcException rpcEx && rpcEx.getCause() == null) {
            log.warn("anchor_broadcast_rejected root={} tx={} error={}", submitted.root(), submitted.tx(), ex.getMessage());
            metrics.receiptAnchor("failed");
            return anchors.update(submitted.failed(ex.getMessage(), now));
        }
        log.warn("anchor_broadcast_uncertain root={} tx={} error={}", submitted.root(), submitted.tx(), ex.toString());
        return Mono.just(submitted);
    }

    /**
     * Moves every non-terminal batch as far as the chain allows: SUBMITTED → FINALIZED (receipts
     * stamped) or FAILED (error, or never seen past its {@code lastValidBlockHeight}); FAILED and
     * PENDING → re-submitted for the same root, or ABANDONED after {@code max-attempts}.
     */
    public Flux<ReceiptAnchor> settle() {
        Flux<ReceiptAnchor> inFlight = anchors.findByStatus(ReceiptAnchor.Status.SUBMITTED, 100)
                .concatMap(a -> check(a).onErrorResume(ex -> {
                    log.warn("anchor_settle_failed root={} tx={} error={}", a.root(), a.tx(), ex.toString());
                    return Mono.empty();
                }));
        // Deferred: the FAILED list is read after the in-flight pass, so a batch that just failed is retried in the same sweep.
        Flux<ReceiptAnchor> retries = Flux.defer(() -> Flux.concat(anchors.findByStatus(ReceiptAnchor.Status.FAILED, 100), anchors.findByStatus(ReceiptAnchor.Status.PENDING, 100)))
                .concatMap(a -> {
                    if (a.attempts() >= props.maxAttempts()) {
                        Instant now = clock.instant();
                        log.error("anchor_abandoned root={} attempts={} lastError={}", a.root(), a.attempts(), a.lastError());
                        metrics.receiptAnchor("abandoned");
                        return anchors.update(a.abandoned("max attempts (" + props.maxAttempts() + ") exhausted: " + a.lastError(), now));
                    }
                    log.info("anchor_resubmit root={} attempt={} previousTx={}", a.root(), a.attempts() + 1, a.tx());
                    return submit(a);
                });
        return Flux.concat(inFlight, retries);
    }

    /** One look at a SUBMITTED batch. Empty when nothing changed. */
    Mono<ReceiptAnchor> check(ReceiptAnchor a) {
        SolanaCluster cluster = props.solanaCluster();
        return rpc.getSignatureStatus(cluster, a.tx()).flatMap(status -> {
            if (status.failed()) {
                return markFailed(a, "on-chain error: " + status.error());
            }
            if ("finalized".equals(status.confirmationStatus())) {
                return finalize(a, status.slot());
            }
            if (status.confirmationStatus() == null) {
                // Never seen. Once the blockhash is dead it can never land: safe to re-anchor.
                return rpc.getBlockHeight(cluster).flatMap(height -> a.lastValidBlockHeight() != null && height > a.lastValidBlockHeight()
                        ? markFailed(a, "never seen and blockhash expired (height " + height + " > " + a.lastValidBlockHeight() + ")")
                        : Mono.<ReceiptAnchor>empty());
            }
            return Mono.empty(); // processed / confirmed: not final yet
        });
    }

    private Mono<ReceiptAnchor> markFailed(ReceiptAnchor a, String error) {
        Instant now = clock.instant();
        log.warn("anchor_failed root={} tx={} attempt={} error={}", a.root(), a.tx(), a.attempts(), error);
        metrics.receiptAnchor("failed");
        return anchors.update(a.failed(error, now));
    }

    private Mono<ReceiptAnchor> finalize(ReceiptAnchor a, Long slotFromStatus) {
        Mono<Long> slot = slotFromStatus != null ? Mono.just(slotFromStatus)
                : rpc.getTransaction(props.solanaCluster(), a.tx()).map(SolanaRpcClient.TransactionInfo::slot).defaultIfEmpty(0L);
        return slot.flatMap(s -> {
            Instant now = clock.instant();
            ReceiptAnchor finalized = a.finalized(s, now);
            return anchors.update(finalized)
                    .flatMap(stored -> anchors.stampReceipts(stored).doOnNext(metrics::anchoredReceipts).thenReturn(stored))
                    .doOnNext(stored -> {
                        metrics.receiptAnchor("finalized");
                        if (stored.submittedAt() != null) {
                            metrics.anchorFinalityLatency(Duration.between(stored.submittedAt(), now));
                        }
                        log.info("anchor_finalized root={} tx={} slot={} receipts={} explorer={}", stored.root(), stored.tx(), stored.slot(),
                                stored.receiptCount(), props.solanaCluster().explorerTxUrl(stored.tx()));
                    });
        });
    }

    /** Polls a SUBMITTED batch until it is finalized or failed, for at most {@code finality-wait}. Returns the last known state. */
    public Mono<ReceiptAnchor> awaitFinality(ReceiptAnchor anchor) {
        if (anchor.status() != ReceiptAnchor.Status.SUBMITTED) {
            return Mono.just(anchor);
        }
        long polls = Math.max(1, props.finalityWait().toMillis() / Math.max(1, props.pollInterval().toMillis()));
        return Flux.interval(props.pollInterval(), props.pollInterval())
                .take(polls)
                .concatMap(i -> check(anchor).onErrorResume(ex -> Mono.empty()))
                .next()
                .defaultIfEmpty(anchor);
    }

    // ---- reads and verification ---------------------------------------------------------------

    public Flux<ReceiptAnchor> recent(int limit) {
        return anchors.findRecent(limit);
    }

    public Mono<ReceiptAnchor> require(String root) {
        String r;
        try {
            r = Digests.requireHash("root", root);
        } catch (IllegalArgumentException ex) {
            return Mono.error(new ControlPlaneExceptions.InvalidRequest("INVALID_ROOT", ex.getMessage()));
        }
        return anchors.findByRoot(r).switchIfEmpty(Mono.error(new ControlPlaneExceptions.NotFound("Anchor " + r)));
    }

    public Flux<ReceiptAnchor.Member> membersOwnedBy(String root, UUID ownerId) {
        return anchors.membersOwnedBy(root, ownerId);
    }

    /**
     * The receipt's inclusion, from the columns the FINALIZED batch stamped on it: the proof is
     * folded back to the root and compared. A receipt in a batch that is not finalized yet reports
     * the batch status and {@code anchored=false}.
     */
    public Mono<Inclusion> inclusion(IntelligenceReceipt receipt) {
        IntelligenceReceipt.Anchor a = receipt.anchor();
        if (a != null && a.tx() != null) {
            boolean valid = MerkleTree.verify(receipt.receiptHash(), a.proof(), a.root());
            return Mono.just(new Inclusion(true, ReceiptAnchor.Status.FINALIZED.name(), a.chain(), a.tx(), a.slot(), a.root(), a.proof(), valid,
                    explorerUrl(a.chain(), a.tx())));
        }
        return anchors.membershipOf(receipt.receiptHash())
                .flatMap(m -> anchors.findByRoot(m.root()).map(batch -> new Inclusion(false, batch.status().name(), batch.chain(), batch.tx(), batch.slot(),
                        m.root(), m.proof(), MerkleTree.verify(receipt.receiptHash(), m.proof(), m.root()), batch.tx() == null ? null : explorerUrl(batch.chain(), batch.tx()))))
                .defaultIfEmpty(Inclusion.none());
    }

    /** Recomputes a batch from its members and reads the memo back from the chain. */
    public Mono<AnchorVerification> verify(String root) {
        return require(root).flatMap(a -> anchors.members(a.root()).collectList().flatMap(members -> {
            List<String> hashes = members.stream().map(ReceiptAnchor.Member::receiptHash).toList();
            String recomputed = hashes.isEmpty() ? null : MerkleTree.of(hashes).root();
            boolean rootMatches = a.root().equals(recomputed);
            boolean countMatches = members.size() == a.receiptCount();
            boolean proofsValid = !members.isEmpty() && members.stream().allMatch(m -> MerkleTree.verify(m.receiptHash(), m.proof(), a.root()));
            Optional<AnchorMemo> memo = AnchorMemo.parse(a.memo());
            boolean memoMatches = memo.isPresent() && memo.get().root().equals(a.root()) && memo.get().count() == a.receiptCount();
            Mono<OnChain> chain = a.tx() == null ? Mono.just(OnChain.skipped("no transaction yet"))
                    : rpc.getTransaction(props.solanaCluster(), a.tx())
                            .map(info -> new OnChain(true, true, info.failed(), info.memos().contains(a.memo()), info.slot(), info.error()))
                            .defaultIfEmpty(new OnChain(true, false, false, false, null, "not finalized on the node (or dropped)"))
                            .onErrorResume(ex -> Mono.just(new OnChain(true, false, false, false, null, "rpc: " + ex.getMessage())));
            return chain.map(onChain -> new AnchorVerification(a.root(), a.status(), a.receiptCount(), members.size(), recomputed, rootMatches,
                    countMatches, proofsValid, memoMatches, a.memo(), a.tx(), a.slot(), a.tx() == null ? null : explorerUrl(a.chain(), a.tx()), onChain));
        })).doOnNext(v -> {
            if (!v.valid()) {
                log.warn("anchor_verification_failed root={} rootOk={} countOk={} proofsOk={} memoOk={} onChain={}", v.root(), v.rootMatches(),
                        v.countMatches(), v.proofsValid(), v.memoMatches(), v.onChain());
            }
        });
    }

    public static String explorerUrl(String chain, String tx) {
        if (chain == null || tx == null || !chain.toLowerCase(java.util.Locale.ROOT).startsWith("solana")) {
            return null;
        }
        return (chain.toLowerCase(java.util.Locale.ROOT).endsWith("devnet") ? SolanaCluster.DEVNET : SolanaCluster.MAINNET_BETA).explorerTxUrl(tx);
    }
}
