package io.lifeengine.cryptobot.application.value;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.lifeengine.cryptobot.application.controlplane.ControlPlaneExceptions;
import io.lifeengine.cryptobot.application.receipt.AnchorProperties;
import io.lifeengine.cryptobot.application.receipt.AnchorService;
import io.lifeengine.cryptobot.application.receipt.ReceiptService;
import io.lifeengine.cryptobot.core.receipts.Digests;
import io.lifeengine.cryptobot.core.receipts.ReceiptKind;
import io.lifeengine.cryptobot.core.receipts.ReceiptSigningKey;
import io.lifeengine.cryptobot.core.value.ValueEvent.AcceptanceProof;
import io.lifeengine.cryptobot.core.value.ValueEvent.AgentIdentity;
import io.lifeengine.cryptobot.core.value.ValueEvent.Contribution;
import io.lifeengine.cryptobot.core.value.ValueEvent.ContributionType;
import io.lifeengine.cryptobot.core.value.ValueEvent.Identity;
import io.lifeengine.cryptobot.core.value.ValueEvent.IdentityKind;
import io.lifeengine.cryptobot.integration.signer.SignerClient;
import io.lifeengine.cryptobot.solana.rpc.Base58;
import io.lifeengine.cryptobot.solana.rpc.SolanaCluster;
import io.lifeengine.cryptobot.solana.rpc.SolanaRpcClient;
import io.lifeengine.cryptobot.solana.tx.SolanaKeypair;
import io.lifeengine.cryptobot.testsupport.InMemoryAnchorRepository;
import io.lifeengine.cryptobot.testsupport.InMemoryControlPlaneRepositories;
import io.lifeengine.cryptobot.testsupport.InMemoryValueEventRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

/**
 * KAN-818 on the real in-memory stores and the real {@link AnchorService}, with a signer that really
 * signs and an RPC that plays the chain: record → signed VALUE_EVENT receipt → anchored batch →
 * FINALIZED → verify. What this does NOT prove is the devnet transaction itself: that needs the
 * signer and a funded devnet key (see the PR).
 */
class ValueEventServiceTest {

    static final UUID OWNER = UUID.fromString("a0000000-0000-4000-8000-000000000001");
    static final UUID OTHER = UUID.fromString("b0000000-0000-4000-8000-000000000002");
    static final Instant T0 = Instant.parse("2026-09-30T10:00:00Z");
    static final String BLOCKHASH = "So11111111111111111111111111111111111111112";

    final SolanaKeypair signerKey = SolanaKeypair.generate();
    final SignerClient signer = mock(SignerClient.class);
    final SolanaRpcClient rpc = mock(SolanaRpcClient.class);
    final InMemoryAnchorRepository anchorStore = new InMemoryAnchorRepository();
    ReceiptService receipts;
    AnchorService anchors;
    ValueEventService service;

    @BeforeEach
    void setUp() {
        InMemoryControlPlaneRepositories.reset();
        InMemoryAnchorRepository.reset();
        InMemoryValueEventRepository.reset();
        receipts = new ReceiptService(InMemoryControlPlaneRepositories.receipts(), ReceiptSigningKey.generate("unit-key"));
        anchors = new AnchorService(anchorStore, signer, rpc,
                new AnchorProperties(false, "devnet", Duration.ofSeconds(60), 256, 2, Duration.ofMillis(300), Duration.ofMillis(10)),
                Clock.fixed(T0.plusSeconds(600), ZoneOffset.UTC));
        service = new ValueEventService(new InMemoryValueEventRepository(), receipts, anchors, Clock.fixed(T0.plusSeconds(120), ZoneOffset.UTC));

        when(signer.identity()).thenReturn(Mono.just(Optional.of(new SignerClient.Identity(signerKey.publicKeyBase58(), "devnet", 2_000_000_000L, List.of()))));
        doAnswer(inv -> {
            byte[] wire = Base64.getDecoder().decode(inv.<String>getArgument(2));
            byte[] message = Arrays.copyOfRange(wire, 65, wire.length);
            byte[] signed = new byte[wire.length];
            signed[0] = 1;
            System.arraycopy(signerKey.sign(message), 0, signed, 1, 64);
            System.arraycopy(message, 0, signed, 65, message.length);
            return Mono.just(new SignerClient.SignResponse(Base64.getEncoder().encodeToString(signed), signerKey.publicKeyBase58(), ""));
        }).when(signer).signAnchor(anyString(), anyInt(), anyString(), anyString());
        when(rpc.getLatestBlockhash(SolanaCluster.DEVNET)).thenReturn(Mono.just(new SolanaRpcClient.LatestBlockhash(BLOCKHASH, 1000L)));
        doAnswer(inv -> Mono.just(Base58.encode(Arrays.copyOfRange(Base64.getDecoder().decode(inv.<String>getArgument(1)), 1, 65))))
                .when(rpc).sendTransaction(eq(SolanaCluster.DEVNET), anyString());
    }

    static ValueEventService.Command command(String evidence, String nonce, Identity acceptor) {
        Identity agent = new Identity("verticals@1", IdentityKind.AGENT);
        return new ValueEventService.Command(agent, new AgentIdentity(agent, "sebas", "claude-sonnet-5-5", null),
                new Contribution(ContributionType.CODE, Digests.sha256(evidence), "github:sebdev89/life-engine-cryptobot-service#pr/48"),
                new AcceptanceProof(acceptor, "human-approval", Digests.sha256("approval-" + evidence), "jira:KAN-818#comment", T0.plusSeconds(60)),
                T0, nonce);
    }

    static final Identity SEBAS = new Identity("sebas", IdentityKind.HUMAN);

    @Test
    @DisplayName("record: a signed VALUE_EVENT receipt commits to the value-event hash; the row is stored; the same event twice is one event")
    void recordsAndIsIdempotent() {
        ValueEventService.View v = service.record(OWNER, command("diff-1", "n-1", SEBAS)).block();
        assertThat(v.event().valueEventHash()).matches("^sha256:[0-9a-f]{64}$");
        assertThat(v.event().tenantId()).isEqualTo(OWNER.toString());
        assertThat(v.anchor().anchored()).isFalse();

        var receipt = receipts.require(OWNER, v.event().receiptHash()).block();
        assertThat(receipt.kind()).isEqualTo(ReceiptKind.VALUE_EVENT);
        assertThat(receipt.body().output().hash()).isEqualTo(v.event().valueEventHash());
        assertThat(receipt.body().inputs()).extracting(i -> i.hash()).containsExactlyInAnyOrder(Digests.sha256("diff-1"), Digests.sha256("approval-diff-1"));

        ValueEventService.View again = service.record(OWNER, command("diff-1", "n-1", SEBAS)).block();
        assertThat(again.event().valueEventHash()).isEqualTo(v.event().valueEventHash());
        assertThat(InMemoryValueEventRepository.EVENTS).hasSize(1);
        assertThat(InMemoryControlPlaneRepositories.RECEIPTS).hasSize(1);
    }

    @Test
    @DisplayName("an event accepted by its own contributor is refused as INVALID_VALUE_EVENT and nothing is stored")
    void selfAcceptanceRefused() {
        assertThatThrownBy(() -> service.record(OWNER, command("diff-1", "n-1", new Identity("verticals@1", IdentityKind.AGENT))).block())
                .isInstanceOf(ControlPlaneExceptions.InvalidRequest.class);
        assertThat(InMemoryValueEventRepository.EVENTS).isEmpty();
        assertThat(InMemoryControlPlaneRepositories.RECEIPTS).isEmpty();
    }

    @Test
    @DisplayName("owner scoping: another owner gets NotFound for get and verify")
    void ownerScoped() {
        String hash = service.record(OWNER, command("diff-1", "n-1", SEBAS)).block().event().valueEventHash();
        assertThatThrownBy(() -> service.get(OTHER, hash).block()).isInstanceOf(ControlPlaneExceptions.NotFound.class);
        assertThatThrownBy(() -> service.verify(OTHER, hash).block()).isInstanceOf(ControlPlaneExceptions.NotFound.class);
        assertThat(service.list(OTHER, 10).collectList().block()).isEmpty();
    }

    @Test
    @DisplayName("verify recomputes: valid when untouched, invalid when the stored canonical JSON is altered")
    void verifyDetectsTampering() {
        String hash = service.record(OWNER, command("diff-1", "n-1", SEBAS)).block().event().valueEventHash();
        assertThat(service.verify(OWNER, hash).block().valid()).isTrue();

        var row = InMemoryValueEventRepository.EVENTS.get(hash);
        InMemoryValueEventRepository.EVENTS.put(hash, new io.lifeengine.cryptobot.infrastructure.persistence.controlplane.ValueEventRepository.Row(
                row.valueEventHash(), row.receiptHash(), row.tenantId(), row.ownerId(), row.contributorId(), row.contributorKind(), row.agentId(),
                row.contributionType(), row.evidenceHash(), row.evidenceRef(), row.acceptorId(), row.acceptanceMethod(), row.acceptanceHash(),
                row.canonical().replace("verticals@1", "someone-else"), row.occurredAt(), row.createdAt()));
        var v = service.verify(OWNER, hash).block();
        assertThat(v.hashMatchesCanonical()).isFalse();
        assertThat(v.valid()).isFalse();
    }

    @Test
    @DisplayName("the loop: recorded events join the anchoring batch, FINALIZED stamps the receipt, inclusion + summary + evidence lookup see it")
    void anchoredEndToEnd() {
        String h1 = service.record(OWNER, command("diff-1", "n-1", SEBAS)).block().event().valueEventHash();
        String h2 = service.record(OWNER, command("diff-2", "n-2", SEBAS)).block().event().valueEventHash();
        assertThat(service.summary(OWNER).block().anchored()).isZero();

        var batch = anchors.anchorPending().block().orElseThrow();
        assertThat(batch.receiptCount()).isEqualTo(2);
        doAnswer(inv -> Mono.just(new SolanaRpcClient.SignatureStatus(inv.getArgument(1), "finalized", false, null, 4242L)))
                .when(rpc).getSignatureStatus(eq(SolanaCluster.DEVNET), anyString());
        anchors.settle().collectList().block();

        ValueEventService.View v = service.get(OWNER, h1).block();
        assertThat(v.anchor().anchored()).isTrue();
        assertThat(v.anchor().chain()).isEqualTo("solana-devnet");
        assertThat(v.anchor().tx()).isEqualTo(batch.tx());
        assertThat(v.anchor().slot()).isEqualTo(4242L);
        assertThat(v.anchor().proofValid()).isTrue();
        assertThat(v.anchor().explorerUrl()).contains(batch.tx()).contains("devnet");

        var verification = service.verify(OWNER, h2).block();
        assertThat(verification.valid()).isTrue();
        assertThat(verification.anchor().anchored()).isTrue();

        var summary = service.summary(OWNER).block();
        assertThat(summary.events()).isEqualTo(2);
        assertThat(summary.anchored()).isEqualTo(2);
        assertThat(summary.byContributionType()).containsEntry("CODE", 2L);
        assertThat(summary.byContributor()).containsEntry("verticals@1", 2L);
        assertThat(summary.byAcceptor()).containsEntry("sebas", 2L);

        assertThat(service.byEvidence(OWNER, Digests.sha256("diff-2")).map(r -> r.valueEventHash()).collectList().block()).containsExactly(h2);
        assertThatThrownBy(() -> service.byEvidence(OWNER, "nope").collectList().block()).isInstanceOf(ControlPlaneExceptions.InvalidRequest.class);
    }
}
