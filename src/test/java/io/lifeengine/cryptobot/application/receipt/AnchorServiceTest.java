package io.lifeengine.cryptobot.application.receipt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.lifeengine.cryptobot.solana.rpc.Base58;
import io.lifeengine.cryptobot.solana.rpc.SolanaCluster;
import io.lifeengine.cryptobot.solana.rpc.SolanaRpcClient;
import io.lifeengine.cryptobot.solana.rpc.SolanaRpcException;
import io.lifeengine.cryptobot.solana.tx.SolanaKeypair;
import io.lifeengine.cryptobot.application.controlplane.ControlPlaneExceptions;
import io.lifeengine.cryptobot.core.receipts.AnchorMemo;
import io.lifeengine.cryptobot.core.receipts.Digests;
import io.lifeengine.cryptobot.core.receipts.IntelligenceReceipt;
import io.lifeengine.cryptobot.core.receipts.MerkleTree;
import io.lifeengine.cryptobot.core.receipts.ReceiptAnchor;
import io.lifeengine.cryptobot.core.receipts.ReceiptBody;
import io.lifeengine.cryptobot.core.receipts.ReceiptInput;
import io.lifeengine.cryptobot.core.receipts.ReceiptKind;
import io.lifeengine.cryptobot.core.receipts.ReceiptSigningKey;
import io.lifeengine.cryptobot.core.receipts.ReproducibilityLevel;
import io.lifeengine.cryptobot.integration.signer.SignerClient;
import io.lifeengine.cryptobot.testsupport.InMemoryAnchorRepository;
import io.lifeengine.cryptobot.testsupport.InMemoryControlPlaneRepositories;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

/**
 * The anchoring batch (KAN-394, Endgame §11) on the in-memory stores, with a signer fake that
 * really signs (so the service's own signature check runs) and an RPC mock that plays the chain:
 * batch → memo → signed → SUBMITTED; finalized → stamped with proofs; dropped → re-anchored for
 * the same root; too many failures → abandoned and re-batched; verify recomputes the root and
 * reads the memo back.
 */
class AnchorServiceTest {

    static final UUID OWNER = UUID.fromString("a0000000-0000-4000-8000-000000000001");
    static final Instant T0 = Instant.parse("2026-09-18T03:00:00Z");
    static final String BLOCKHASH_1 = "So11111111111111111111111111111111111111112";
    static final String BLOCKHASH_2 = "9xQeWvG816bUx9EPjHmaT23yvVM2ZWbrrpZb9PusVFin";

    final SolanaKeypair signerKey = SolanaKeypair.generate();
    final SignerClient signer = mock(SignerClient.class);
    final SolanaRpcClient rpc = mock(SolanaRpcClient.class);
    final InMemoryAnchorRepository anchors = new InMemoryAnchorRepository();
    final AtomicInteger blockhashCalls = new AtomicInteger();
    ReceiptService receipts;
    AnchorService service;
    AnchorProperties props;

    @BeforeEach
    void setUp() {
        InMemoryControlPlaneRepositories.reset();
        receipts = new ReceiptService(InMemoryControlPlaneRepositories.receipts(), ReceiptSigningKey.generate("unit-key"));
        props = new AnchorProperties(false, "devnet", Duration.ofSeconds(60), 256, 2, Duration.ofMillis(300), Duration.ofMillis(10));
        service = new AnchorService(anchors, signer, rpc, props, Clock.fixed(T0, ZoneOffset.UTC));

        when(signer.identity()).thenReturn(Mono.just(Optional.of(new SignerClient.Identity(signerKey.publicKeyBase58(), "devnet", 2_000_000_000L, List.of()))));
        signerSignsWith(signerKey);
        when(rpc.getLatestBlockhash(SolanaCluster.DEVNET)).thenAnswer(inv ->
                Mono.just(new SolanaRpcClient.LatestBlockhash(blockhashCalls.getAndIncrement() == 0 ? BLOCKHASH_1 : BLOCKHASH_2, 1000L)));
        doAnswer(inv -> {
            byte[] wire = Base64.getDecoder().decode(inv.<String>getArgument(1));
            return Mono.just(Base58.encode(Arrays.copyOfRange(wire, 1, 65)));
        }).when(rpc).sendTransaction(eq(SolanaCluster.DEVNET), anyString());
    }

    /** The signer fake: signs whatever message it gets with {@code key} and returns the wire form, like cryptobot-signer does. */
    void signerSignsWith(SolanaKeypair key) {
        doAnswer(inv -> {
            byte[] wire = Base64.getDecoder().decode(inv.<String>getArgument(2));
            byte[] message = Arrays.copyOfRange(wire, 65, wire.length);
            byte[] sig = key.sign(message);
            byte[] signed = new byte[wire.length];
            signed[0] = 1;
            System.arraycopy(sig, 0, signed, 1, 64);
            System.arraycopy(message, 0, signed, 65, message.length);
            return Mono.just(new SignerClient.SignResponse(Base64.getEncoder().encodeToString(signed), key.publicKeyBase58(), ""));
        }).when(signer).signAnchor(anyString(), anyInt(), anyString(), anyString());
    }

    IntelligenceReceipt issue(String nonce) {
        ReceiptBody body = new ReceiptBody(null, ReceiptKind.WALLET_SNAPSHOT, OWNER.toString(), OWNER.toString(), "test-agent@1", List.of(),
                List.of(new ReceiptInput(ReceiptInput.WALLET_SNAPSHOT, Digests.sha256("snapshot-" + nonce))), null, null, null, null, Map.of(),
                new ReceiptBody.Output(Digests.sha256("output-of-" + nonce), "test/1", null), null, null,
                ReproducibilityLevel.L0_SIGNED, T0, T0.plusSeconds(1), nonce, null);
        return receipts.issue(ReceiptDraft.of(body)).block();
    }

    List<IntelligenceReceipt> issueThree() {
        return List.of(issue("r1"), issue("r2"), issue("r3"));
    }

    void chainSays(String confirmation, Long slot, boolean failed) {
        doAnswer(inv -> Mono.just(new SolanaRpcClient.SignatureStatus(inv.getArgument(1), confirmation, failed, failed ? "{\"InstructionError\":[0,\"Custom\"]}" : null, slot)))
                .when(rpc).getSignatureStatus(eq(SolanaCluster.DEVNET), anyString());
    }

    @Test
    @DisplayName("a batch: Merkle root over the sorted receipts, memo ir/1, members with proofs persisted before signing, SUBMITTED with the tx id")
    void opensABatchAndSubmitsIt() {
        List<IntelligenceReceipt> issued = issueThree();
        ReceiptAnchor a = service.anchorPending().block().orElseThrow();

        MerkleTree expected = MerkleTree.of(issued.stream().map(IntelligenceReceipt::receiptHash).toList());
        assertThat(a.root()).isEqualTo(expected.root());
        assertThat(a.status()).isEqualTo(ReceiptAnchor.Status.SUBMITTED);
        assertThat(a.attempts()).isEqualTo(1);
        assertThat(a.tx()).isNotBlank();
        assertThat(a.feePayer()).isEqualTo(signerKey.publicKeyBase58());
        assertThat(a.blockhash()).isEqualTo(BLOCKHASH_1);
        assertThat(a.lastValidBlockHeight()).isEqualTo(1000L);
        assertThat(a.chain()).isEqualTo("solana-devnet");
        assertThat(AnchorMemo.parse(a.memo())).contains(new AnchorMemo(expected.root(), 3, T0));
        verify(signer).signAnchor(eq(expected.root()), eq(3), anyString(), eq(signerKey.publicKeyBase58()));
        verify(rpc).sendTransaction(eq(SolanaCluster.DEVNET), anyString());

        List<ReceiptAnchor.Member> members = anchors.members(a.root()).collectList().block();
        assertThat(members).hasSize(3);
        for (ReceiptAnchor.Member m : members) {
            assertThat(MerkleTree.verify(m.receiptHash(), m.proof(), a.root())).isTrue();
        }
        // Not finalized: no receipt carries the anchor yet, and nothing is left to batch.
        assertThat(InMemoryControlPlaneRepositories.RECEIPTS.values()).allMatch(r -> r.anchor() == null);
        assertThat(service.anchorPending().block()).isEmpty();
        assertThat(service.inclusion(issued.get(0)).block()).satisfies(inc -> {
            assertThat(inc.anchored()).isFalse();
            assertThat(inc.status()).isEqualTo("SUBMITTED");
            assertThat(inc.proofValid()).isTrue();
        });
    }

    @Test
    @DisplayName("finalized ⇒ FINALIZED, every receipt stamped with chain/tx/slot/root/proof; inclusion and batch verification pass; confirmed is not enough")
    void finalizedStampsReceipts() {
        List<IntelligenceReceipt> issued = issueThree();
        ReceiptAnchor submitted = service.anchorPending().block().orElseThrow();

        chainSays("confirmed", 70L, false);
        assertThat(service.settle().collectList().block()).isEmpty();
        assertThat(anchors.findByRoot(submitted.root()).block().status()).isEqualTo(ReceiptAnchor.Status.SUBMITTED);
        assertThat(InMemoryControlPlaneRepositories.RECEIPTS.values()).allMatch(r -> r.anchor() == null);

        chainSays("finalized", 77L, false);
        when(rpc.getTransaction(eq(SolanaCluster.DEVNET), anyString())).thenAnswer(inv ->
                Mono.just(new SolanaRpcClient.TransactionInfo(inv.getArgument(1), 77L, T0.plusSeconds(20), false, null, List.of(submitted.memo()))));
        List<ReceiptAnchor> settled = service.settle().collectList().block();
        assertThat(settled).hasSize(1);
        ReceiptAnchor fin = settled.get(0);
        assertThat(fin.status()).isEqualTo(ReceiptAnchor.Status.FINALIZED);
        assertThat(fin.slot()).isEqualTo(77L);
        assertThat(fin.finalizedAt()).isEqualTo(T0);

        for (IntelligenceReceipt r : issued) {
            IntelligenceReceipt stored = InMemoryControlPlaneRepositories.RECEIPTS.get(r.receiptHash());
            assertThat(stored.anchor()).isNotNull();
            assertThat(stored.anchor().chain()).isEqualTo("solana-devnet");
            assertThat(stored.anchor().tx()).isEqualTo(fin.tx());
            assertThat(stored.anchor().slot()).isEqualTo(77L);
            assertThat(stored.anchor().root()).isEqualTo(fin.root());
            assertThat(MerkleTree.verify(stored.receiptHash(), stored.anchor().proof(), stored.anchor().root())).isTrue();
            assertThat(stored.canonicalJson()).as("the anchor is outside the hash").isEqualTo(r.canonicalJson());
            AnchorService.Inclusion inc = service.inclusion(stored).block();
            assertThat(inc.anchored()).isTrue();
            assertThat(inc.proofValid()).isTrue();
            assertThat(inc.explorerUrl()).isEqualTo("https://explorer.solana.com/tx/" + fin.tx() + "?cluster=devnet");
        }
        assertThat(anchors.countUnanchored().block()).isZero();

        AnchorService.AnchorVerification v = service.verify(fin.root()).block();
        assertThat(v.valid()).isTrue();
        assertThat(v.recomputedRoot()).isEqualTo(fin.root());
        assertThat(v.rootMatches()).isTrue();
        assertThat(v.countMatches()).isTrue();
        assertThat(v.proofsValid()).isTrue();
        assertThat(v.memoMatches()).isTrue();
        assertThat(v.onChain().checked()).isTrue();
        assertThat(v.onChain().found()).isTrue();
        assertThat(v.onChain().memoMatches()).isTrue();
        assertThat(v.onChain().slot()).isEqualTo(77L);

        // A tampered proof no longer folds to the root.
        IntelligenceReceipt first = InMemoryControlPlaneRepositories.RECEIPTS.get(issued.get(0).receiptHash());
        List<String> bad = new ArrayList<>(first.anchor().proof());
        bad.set(0, "R:" + Digests.sha256("evil"));
        IntelligenceReceipt tampered = first.withAnchor(new IntelligenceReceipt.Anchor(first.anchor().chain(), first.anchor().tx(), 77L, first.anchor().root(), bad));
        assertThat(service.inclusion(tampered).block().proofValid()).isFalse();
    }

    @Test
    @DisplayName("dropped (never seen, blockhash expired) or errored on chain ⇒ FAILED, then re-anchored for the SAME root and memo with a new transaction")
    void reanchorsIdempotentlyByRoot() {
        issueThree();
        ReceiptAnchor first = service.anchorPending().block().orElseThrow();

        chainSays(null, null, false);
        when(rpc.getBlockHeight(SolanaCluster.DEVNET)).thenReturn(Mono.just(900L));
        assertThat(service.settle().collectList().block()).as("blockhash still alive: nothing changes").isEmpty();

        when(rpc.getBlockHeight(SolanaCluster.DEVNET)).thenReturn(Mono.just(1001L));
        List<ReceiptAnchor> settled = service.settle().collectList().block();
        // The same sweep marks it FAILED and re-submits it (attempts < max): two transitions on one root.
        assertThat(settled).extracting(ReceiptAnchor::status).containsExactly(ReceiptAnchor.Status.FAILED, ReceiptAnchor.Status.SUBMITTED);
        ReceiptAnchor second = settled.get(1);
        assertThat(second.root()).isEqualTo(first.root());
        assertThat(second.memo()).isEqualTo(first.memo());
        assertThat(second.attempts()).isEqualTo(2);
        assertThat(second.blockhash()).isEqualTo(BLOCKHASH_2);
        assertThat(second.tx()).isNotEqualTo(first.tx());
        assertThat(anchors.members(first.root()).collectList().block()).hasSize(3);
        assertThat(InMemoryAnchorRepository.ANCHORS).hasSize(1);
        verify(rpc, times(2)).sendTransaction(eq(SolanaCluster.DEVNET), anyString());

        // On-chain error on the second try: FAILED again, and now attempts == max ⇒ ABANDONED, receipts free for a new batch.
        chainSays("finalized", 80L, true);
        List<ReceiptAnchor> next = service.settle().collectList().block();
        assertThat(next).extracting(ReceiptAnchor::status).containsExactly(ReceiptAnchor.Status.FAILED, ReceiptAnchor.Status.ABANDONED);
        assertThat(InMemoryControlPlaneRepositories.RECEIPTS.values()).allMatch(r -> r.anchor() == null);
        assertThat(anchors.unanchoredReceiptHashes(10).collectList().block()).hasSize(3);

        // Same receipts ⇒ same root: the abandoned batch is reopened, attempts start over, a new memo tx goes out.
        ReceiptAnchor reopened = service.anchorPending().block().orElseThrow();
        assertThat(reopened.root()).isEqualTo(first.root());
        assertThat(reopened.status()).isEqualTo(ReceiptAnchor.Status.SUBMITTED);
        assertThat(reopened.attempts()).isEqualTo(1);
        assertThat(InMemoryAnchorRepository.ANCHORS).hasSize(1);
    }

    @Test
    @DisplayName("signer refusal or RPC rejection ⇒ a FAILED attempt with no transaction on the chain; an RPC timeout leaves the row in flight")
    void failuresBeforeAndDuringBroadcast() {
        issueThree();
        doReturn(Mono.error(new SignerClient.SignerRefused("HTTP 403 {\"reason\":\"memo_format\"}"))).when(signer).signAnchor(anyString(), anyInt(), anyString(), anyString());
        ReceiptAnchor refused = service.anchorPending().block().orElseThrow();
        assertThat(refused.status()).isEqualTo(ReceiptAnchor.Status.FAILED);
        assertThat(refused.tx()).isNull();
        assertThat(refused.attempts()).isEqualTo(1);
        assertThat(refused.lastError()).contains("memo_format");
        verify(rpc, never()).sendTransaction(any(), anyString());

        // Retry via settle with the node rejecting the broadcast: nothing on the chain, FAILED with the reason.
        signerSignsWith(signerKey);
        doReturn(Mono.error(new SolanaRpcException("sendTransaction", -32002, "Transaction simulation failed", null))).when(rpc).sendTransaction(eq(SolanaCluster.DEVNET), anyString());
        List<ReceiptAnchor> retried = service.settle().collectList().block();
        assertThat(retried).hasSize(1);
        assertThat(retried.get(0).status()).isEqualTo(ReceiptAnchor.Status.FAILED);
        assertThat(retried.get(0).attempts()).isEqualTo(2);
        assertThat(retried.get(0).lastError()).contains("simulation failed");

        // A transport failure is uncertain: the row keeps its signature and stays SUBMITTED for the next sweep.
        props = new AnchorProperties(false, "devnet", Duration.ofSeconds(60), 256, 5, Duration.ofMillis(300), Duration.ofMillis(10));
        service = new AnchorService(anchors, signer, rpc, props, Clock.fixed(T0, ZoneOffset.UTC));
        doReturn(Mono.error(new SolanaRpcException("sendTransaction", -1, "timeout", null, new java.util.concurrent.TimeoutException()))).when(rpc).sendTransaction(eq(SolanaCluster.DEVNET), anyString());
        List<ReceiptAnchor> uncertain = service.settle().collectList().block();
        assertThat(uncertain.get(0).status()).isEqualTo(ReceiptAnchor.Status.SUBMITTED);
        assertThat(uncertain.get(0).tx()).isNotNull();
        assertThat(uncertain.get(0).attempts()).isEqualTo(3);
    }

    @Test
    @DisplayName("a signer that returns another message or another key is refused by the service's own check")
    void signerOutputIsVerified() {
        issueThree();
        SolanaKeypair other = SolanaKeypair.generate();
        signerSignsWith(other);
        ReceiptAnchor a = service.anchorPending().block().orElseThrow();
        assertThat(a.status()).isEqualTo(ReceiptAnchor.Status.FAILED);
        assertThat(a.lastError()).contains("does not verify");
        verify(rpc, never()).sendTransaction(any(), anyString());
    }

    @Test
    @DisplayName("verify: the memo read back from the chain must be ours; an unknown root is a 404; a signer on another cluster is refused")
    void verificationAndGuards() {
        issueThree();
        ReceiptAnchor a = service.anchorPending().block().orElseThrow();
        when(rpc.getTransaction(eq(SolanaCluster.DEVNET), anyString())).thenReturn(Mono.just(
                new SolanaRpcClient.TransactionInfo(a.tx(), 5L, null, false, null, List.of("ir/1 root=" + Digests.sha256("evil") + " n=3 ts=2026-09-18T03:00:00Z"))));
        AnchorService.AnchorVerification v = service.verify(a.root()).block();
        assertThat(v.rootMatches()).isTrue();
        assertThat(v.proofsValid()).isTrue();
        assertThat(v.onChain().found()).isTrue();
        assertThat(v.onChain().memoMatches()).isFalse();
        assertThat(v.valid()).isFalse();

        doReturn(Mono.empty()).when(rpc).getTransaction(eq(SolanaCluster.DEVNET), anyString());
        AnchorService.AnchorVerification notYet = service.verify(a.root()).block();
        assertThat(notYet.onChain().found()).isFalse();
        assertThat(notYet.valid()).isFalse();

        assertThatThrownBy(() -> service.verify(Digests.sha256("nope")).block()).isInstanceOf(ControlPlaneExceptions.NotFound.class);
        assertThatThrownBy(() -> service.verify("garbage").block()).isInstanceOf(ControlPlaneExceptions.InvalidRequest.class);

        doReturn(Mono.just(Optional.of(new SignerClient.Identity(signerKey.publicKeyBase58(), "mainnet-beta", 1L, List.of())))).when(signer).identity();
        ReceiptAnchor retried = service.submit(a.failed("x", T0)).block();
        assertThat(retried.status()).isEqualTo(ReceiptAnchor.Status.FAILED);
        assertThat(retried.lastError()).contains("not on devnet");

        assertThatThrownBy(() -> new AnchorProperties(true, "mainnet", null, 0, 0, null, null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("awaitFinality polls until finalized (or gives up after finality-wait and leaves the row SUBMITTED)")
    void awaitFinality() {
        issueThree();
        ReceiptAnchor a = service.anchorPending().block().orElseThrow();
        AtomicInteger polls = new AtomicInteger();
        doAnswer(inv -> Mono.just(new SolanaRpcClient.SignatureStatus(inv.getArgument(1), polls.incrementAndGet() < 3 ? "confirmed" : "finalized", false, null, 91L)))
                .when(rpc).getSignatureStatus(eq(SolanaCluster.DEVNET), anyString());
        ReceiptAnchor fin = service.awaitFinality(a).block();
        assertThat(fin.status()).isEqualTo(ReceiptAnchor.Status.FINALIZED);
        assertThat(fin.slot()).isEqualTo(91L);
        assertThat(polls.get()).isEqualTo(3);
        assertThat(InMemoryControlPlaneRepositories.RECEIPTS.values()).allMatch(r -> r.anchor() != null && r.anchor().tx().equals(fin.tx()));

        InMemoryControlPlaneRepositories.reset();
        issue("r9");
        ReceiptAnchor b = service.anchorPending().block().orElseThrow();
        chainSays("confirmed", 92L, false);
        ReceiptAnchor still = service.awaitFinality(b).block();
        assertThat(still.status()).isEqualTo(ReceiptAnchor.Status.SUBMITTED);
        assertThat(service.sweep(false).block().pending()).isEqualTo(1L);
    }

    @Test
    @DisplayName("KAN-596 AnchorPort.anchor(root, count): signs and broadcasts an explicit root without touching the persisted batch flow")
    void anchorPortSignsAndBroadcastsAnExplicitRoot() {
        String root = Digests.sha256("port-root");
        io.lifeengine.cryptobot.core.ports.ChainExecutionPort.Submission submission = service.anchor(root, 5).block();

        assertThat(submission.signature()).isNotBlank();
        verify(signer).signAnchor(eq(root), eq(5), anyString(), eq(signerKey.publicKeyBase58()));
        verify(rpc).sendTransaction(eq(SolanaCluster.DEVNET), anyString());
        // Nothing was persisted: this is the port's generic primitive, not the sweep's write path.
        assertThat(InMemoryAnchorRepository.ANCHORS).isEmpty();
    }

    @Test
    @DisplayName("KAN-596 AnchorPort.anchor(root, count): a signer refusal propagates as an error, nothing broadcast")
    void anchorPortPropagatesSignerRefusal() {
        doReturn(Mono.error(new SignerClient.SignerRefused("HTTP 403 {\"reason\":\"memo_format\"}"))).when(signer).signAnchor(anyString(), anyInt(), anyString(), anyString());
        String root = Digests.sha256("port-root-refused");
        assertThatThrownBy(() -> service.anchor(root, 2).block()).isInstanceOf(SignerClient.SignerRefused.class);
        verify(rpc, never()).sendTransaction(any(), anyString());
    }

    @Test
    @DisplayName("the memo is the only thing that leaves: root, count, time — and the text the signer sees is byte-identical")
    void memoContent() {
        issueThree();
        ReceiptAnchor a = service.anchorPending().block().orElseThrow();
        assertThat(a.memo()).matches("^ir/1 root=sha256:[0-9a-f]{64} n=3 ts=2026-09-18T03:00:00Z$");
        assertThat(a.memo().getBytes(StandardCharsets.UTF_8).length).isLessThan(128);
        assertThat(a.memo()).doesNotContain(OWNER.toString());
        for (IntelligenceReceipt r : InMemoryControlPlaneRepositories.RECEIPTS.values()) {
            assertThat(a.memo()).doesNotContain(r.receiptHash().substring(7));
        }
    }
}
