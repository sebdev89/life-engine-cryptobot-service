package io.lifeengine.cryptobot.application.receipt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.lifeengine.cryptobot.application.controlplane.ControlPlaneExceptions;
import io.lifeengine.cryptobot.core.receipts.Digests;
import io.lifeengine.cryptobot.core.receipts.IntelligenceReceipt;
import io.lifeengine.cryptobot.core.receipts.ReceiptBody;
import io.lifeengine.cryptobot.core.receipts.ReceiptCanonicalizer;
import io.lifeengine.cryptobot.core.receipts.ReceiptEdge;
import io.lifeengine.cryptobot.core.receipts.ReceiptInput;
import io.lifeengine.cryptobot.core.receipts.ReceiptKind;
import io.lifeengine.cryptobot.core.receipts.ReceiptSigningKey;
import io.lifeengine.cryptobot.core.receipts.ReproducibilityLevel;
import io.lifeengine.cryptobot.testsupport.InMemoryControlPlaneRepositories;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The invariants of the receipt layer (KAN-391, Endgame §6-7), on the real in-memory store:
 * content addressing, the parents property, replay, tenant scoping and {@code verify}.
 */
class ReceiptServiceTest {

    static final UUID OWNER = UUID.fromString("a0000000-0000-4000-8000-000000000001");
    static final String TENANT = OWNER.toString();
    static final Instant T0 = Instant.parse("2026-09-15T12:00:00Z");

    private final ReceiptSigningKey key = ReceiptSigningKey.generate("unit-key");
    private ReceiptService service;

    @BeforeEach
    void setUp() {
        InMemoryControlPlaneRepositories.reset();
        service = new ReceiptService(InMemoryControlPlaneRepositories.receipts(), key);
    }

    static ReceiptBody body(ReceiptKind kind, String nonce, List<String> parents, String tenant) {
        return new ReceiptBody(null, kind, tenant, OWNER.toString(), "test-agent@1", parents,
                List.of(new ReceiptInput(ReceiptInput.WALLET_SNAPSHOT, Digests.sha256("snapshot"))), null, null, null, null, Map.of(),
                new ReceiptBody.Output(Digests.sha256("output-of-" + nonce), "test/1", null), new ReceiptBody.Compute(null, null, 1, null), null,
                ReproducibilityLevel.L0_SIGNED, T0, T0.plusSeconds(1), nonce, null);
    }

    static ReceiptBody body(ReceiptKind kind, String nonce, List<String> parents) {
        return body(kind, nonce, parents, TENANT);
    }

    @Test
    @DisplayName("the id is the content: same body ⇒ same hash and one row; a different parent ⇒ a different id")
    void contentAddressed() {
        IntelligenceReceipt root = service.issue(ReceiptDraft.of(body(ReceiptKind.WALLET_SNAPSHOT, "snap-1", List.of()))).block();
        IntelligenceReceipt again = service.issue(ReceiptDraft.of(body(ReceiptKind.WALLET_SNAPSHOT, "snap-1", List.of()))).block();
        assertThat(again.receiptHash()).isEqualTo(root.receiptHash());
        assertThat(InMemoryControlPlaneRepositories.RECEIPTS).hasSize(1);
        assertThat(root.receiptHash()).isEqualTo(ReceiptCanonicalizer.receiptHash(root.canonicalJson().getBytes(StandardCharsets.UTF_8)));
        assertThat(root.canonicalJson()).doesNotContain(" ").doesNotContain("\n");

        IntelligenceReceipt other = service.issue(ReceiptDraft.of(body(ReceiptKind.WALLET_SNAPSHOT, "snap-2", List.of()))).block();
        IntelligenceReceipt childOfRoot = service.issue(ReceiptDraft.of(body(ReceiptKind.RISK_DECISION, "risk-a", List.of(root.receiptHash())))).block();
        IntelligenceReceipt childOfOther = service.issue(ReceiptDraft.of(body(ReceiptKind.RISK_DECISION, "risk-b", List.of(other.receiptHash())))).block();
        assertThat(childOfRoot.receiptHash()).isNotEqualTo(childOfOther.receiptHash());
        assertThat(childOfRoot.canonicalJson()).contains(root.receiptHash());
        assertThat(InMemoryControlPlaneRepositories.EDGES).extracting(ReceiptEdge::childHash, ReceiptEdge::parentHash, ReceiptEdge::role)
                .contains(org.assertj.core.groups.Tuple.tuple(childOfRoot.receiptHash(), root.receiptHash(), ReceiptEdge.Role.DERIVES_FROM));
    }

    @Test
    @DisplayName("a receipt cannot name a parent that does not exist, one of another tenant, or itself")
    void parentsMustExistInTheTenant() {
        String ghost = Digests.sha256("never issued");
        assertThatThrownBy(() -> service.issue(ReceiptDraft.of(body(ReceiptKind.RISK_DECISION, "risk-1", List.of(ghost)))).block())
                .isInstanceOf(ControlPlaneExceptions.Conflict.class).hasMessageContaining(ghost);

        IntelligenceReceipt theirs = service.issue(ReceiptDraft.of(body(ReceiptKind.WALLET_SNAPSHOT, "snap-x", List.of(), "other-tenant"))).block();
        assertThatThrownBy(() -> service.issue(ReceiptDraft.of(body(ReceiptKind.RISK_DECISION, "risk-2", List.of(theirs.receiptHash())))).block())
                .isInstanceOf(ControlPlaneExceptions.Conflict.class).hasMessageContaining("another tenant");

        // Self-reference is impossible by construction: the hash does not exist until the body — parents included — is fixed.
        IntelligenceReceipt root = service.issue(ReceiptDraft.of(body(ReceiptKind.WALLET_SNAPSHOT, "snap-1", List.of()))).block();
        ReceiptBody selfNamed = body(ReceiptKind.WALLET_SNAPSHOT, "snap-1", List.of(root.receiptHash()));
        assertThat(service.seal(selfNamed).receiptHash()).isNotEqualTo(root.receiptHash());
        assertThatThrownBy(() -> new ReceiptEdge(root.receiptHash(), root.receiptHash(), ReceiptEdge.Role.DERIVES_FROM))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(InMemoryControlPlaneRepositories.RECEIPTS).hasSize(2);
    }

    @Test
    @DisplayName("one nonce, one receipt: a replay with a different body is refused")
    void nonceIsUniquePerTenant() {
        service.issue(ReceiptDraft.of(body(ReceiptKind.WALLET_SNAPSHOT, "snap-1", List.of()))).block();
        ReceiptBody replay = new ReceiptBody(null, ReceiptKind.WALLET_SNAPSHOT, TENANT, OWNER.toString(), "test-agent@2", List.of(), List.of(), null, null, null, null,
                Map.of(), new ReceiptBody.Output(Digests.sha256("something else"), "test/1", null), null, null, ReproducibilityLevel.L0_SIGNED, T0, T0, "snap-1", null);
        assertThatThrownBy(() -> service.issue(ReceiptDraft.of(replay)).block())
                .isInstanceOf(ControlPlaneExceptions.Conflict.class).hasMessageContaining("nonce");
        // Same nonce under another tenant is a different receipt.
        assertThat(service.issue(ReceiptDraft.of(body(ReceiptKind.WALLET_SNAPSHOT, "snap-1", List.of(), "other-tenant"))).block()).isNotNull();
        assertThat(InMemoryControlPlaneRepositories.RECEIPTS).hasSize(2);
    }

    @Test
    @DisplayName("verify recomputes: a stored receipt passes; a tampered body, hash or signature fails on exactly that check")
    void verifyRecomputesEverything() {
        IntelligenceReceipt root = service.issue(ReceiptDraft.of(body(ReceiptKind.WALLET_SNAPSHOT, "snap-1", List.of()))).block();
        IntelligenceReceipt child = service.issue(ReceiptDraft.of(body(ReceiptKind.RISK_DECISION, "risk-1", List.of(root.receiptHash())))).block();

        ReceiptService.Verification ok = service.verify(OWNER, child.receiptHash()).block();
        assertThat(ok.valid()).isTrue();
        assertThat(ok.hashMatchesCanonical()).isTrue();
        assertThat(ok.bodyMatchesCanonical()).isTrue();
        assertThat(ok.signatureValid()).isTrue();
        assertThat(ok.parentsPresent()).isTrue();
        assertThat(ok.keyId()).isEqualTo("unit-key");
        assertThat(ok.reproduced()).isNull(); // L0: nothing to re-execute (KAN-392 only re-runs L1 receipts of a known engine)
        assertThat(ok.reproduction().reason()).isEqualTo(DeterministicReproducer.REASON_NOT_L1);

        // Another owner cannot even see it.
        assertThatThrownBy(() -> service.verify(UUID.randomUUID(), child.receiptHash()).block()).isInstanceOf(ControlPlaneExceptions.NotFound.class);
        assertThatThrownBy(() -> service.verify(OWNER, "sha256:nope").block()).isInstanceOf(ControlPlaneExceptions.InvalidRequest.class);

        // Tampered body (the JSON says one thing, the canonical bytes another).
        Map<String, Object> tampered = new HashMap<>(child.body().toMap());
        tampered.put("agentId", "somebody-else@9");
        IntelligenceReceipt bodyTampered = new IntelligenceReceipt(child.receiptHash(), child.domain(), ReceiptBody.fromMap(tampered), child.canonicalJson(),
                child.signature(), null, child.createdAt());
        ReceiptService.Verification v1 = service.verify(bodyTampered).block();
        assertThat(v1.bodyMatchesCanonical()).isFalse();
        assertThat(v1.hashMatchesCanonical()).isTrue();
        assertThat(v1.signatureValid()).isTrue();
        assertThat(v1.valid()).isFalse();

        // Tampered canonical bytes (the id no longer matches).
        IntelligenceReceipt bytesTampered = new IntelligenceReceipt(child.receiptHash(), child.domain(), child.body(), child.canonicalJson().replace("risk-1", "risk-9"),
                child.signature(), null, child.createdAt());
        ReceiptService.Verification v2 = service.verify(bytesTampered).block();
        assertThat(v2.hashMatchesCanonical()).isFalse();
        assertThat(v2.valid()).isFalse();

        // Signature by another key with the same key id: refused.
        ReceiptSigningKey impostor = ReceiptSigningKey.generate("unit-key");
        String forged = Base64.getEncoder().encodeToString(impostor.sign(ReceiptCanonicalizer.signingMessage(child.receiptHash())));
        IntelligenceReceipt sigTampered = new IntelligenceReceipt(child.receiptHash(), child.domain(), child.body(), child.canonicalJson(),
                new IntelligenceReceipt.Signature("ed25519", "unit-key", forged), null, child.createdAt());
        ReceiptService.Verification v3 = service.verify(sigTampered).block();
        assertThat(v3.signatureValid()).isFalse();
        assertThat(v3.hashMatchesCanonical()).isTrue();
        assertThat(v3.valid()).isFalse();

        // A parent that disappeared (or was never there) is reported as such.
        IntelligenceReceipt orphan = new IntelligenceReceipt(child.receiptHash(), child.domain(),
                ReceiptBody.fromMap(child.body().toMap()), child.canonicalJson(), child.signature(), null, child.createdAt());
        InMemoryControlPlaneRepositories.RECEIPTS.remove(root.receiptHash());
        assertThat(service.verify(orphan).block().parentsPresent()).isFalse();
    }

    @Test
    @DisplayName("the body never accepts what would make the hash ambiguous: null, floats, a malformed hash")
    void bodyRefusesAmbiguity() {
        assertThatThrownBy(() -> body(ReceiptKind.WALLET_SNAPSHOT, "n", List.of("sha256:short")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("parents[]");
        assertThatThrownBy(() -> new ReceiptInput("X", "not-a-hash")).isInstanceOf(IllegalArgumentException.class);
        Map<String, Object> withFloat = Map.of("temperature", 0.7);
        ReceiptBody floaty = new ReceiptBody(null, ReceiptKind.MARKET_ANALYSIS, TENANT, OWNER.toString(), "a", List.of(), List.of(), null, null, null, null,
                withFloat, new ReceiptBody.Output(Digests.sha256("o"), "s", null), null, null, ReproducibilityLevel.L0_SIGNED, T0, T0, "n", null);
        assertThatThrownBy(() -> ReceiptCanonicalizer.canonicalBytes(floaty)).hasMessageContaining("floating-point");
    }
}
