package io.lifeengine.cryptobot.signer;

import static org.assertj.core.api.Assertions.assertThat;

import io.lifeengine.cryptobot.signer.solana.LegacyTransaction;
import io.lifeengine.cryptobot.signer.solana.SolanaKeypair;
import io.lifeengine.cryptobot.signer.solana.SystemProgram;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class SignerControllerTest {

    static final SolanaKeypair KEY = SolanaKeypair.generate();
    static final SolanaKeypair VALIDATOR = SolanaKeypair.generate();
    static final String VAULT = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v";
    static final String BLOCKHASH = "So11111111111111111111111111111111111111112";

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("signer.keypair-json", () -> SigningPolicyTest.keyJson(KEY));
        r.add("signer.token", () -> "test-token");
        r.add("signer.allowed-destinations", () -> VAULT);
        r.add("signer.max-lamports", () -> "1000000");
        r.add("signer.validator-public-key", VALIDATOR::publicKeyBase58);
    }

    @Autowired private WebTestClient web;

    static Map<String, Object> attestation(AttestationVerifier.Attestation a) {
        return Map.of("payload", a.payload(), "signature", a.signature());
    }

    @Test
    void identityRequiresTheToken() {
        web.get().uri("/api/signer/identity").exchange().expectStatus().isUnauthorized();
        web.get().uri("/api/signer/identity").header("X-Signer-Token", "wrong").exchange().expectStatus().isUnauthorized();
        web.get().uri("/api/signer/identity").header("X-Signer-Token", "test-token").exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.publicKey").isEqualTo(KEY.publicKeyBase58()).jsonPath("$.maxLamports").isEqualTo(1000000)
                .jsonPath("$.attestationRequired").isEqualTo(true).jsonPath("$.validatorPublicKey").isEqualTo(VALIDATOR.publicKeyBase58());
    }

    @Test
    void signsWithAValidAttestationAndTheSignatureVerifiesAgainstTheKey() {
        String me = KEY.publicKeyBase58();
        LegacyTransaction tx = new LegacyTransaction(me, BLOCKHASH, List.of(SystemProgram.transfer(me, VAULT, 1234L)));
        AttestationVerifier.Attestation att = Attestations.fresh(VALIDATOR, "p1", tx.serializeMessage(), Instant.now().getEpochSecond());
        byte[] body = web.post().uri("/api/signer/sign").header("X-Signer-Token", "test-token")
                .bodyValue(Map.of("proposalId", "p1", "unsignedTransactionBase64", tx.unsignedBase64(), "expectedFeePayer", me, "cluster", "devnet", "attestation", attestation(att)))
                .exchange().expectStatus().isOk().expectBody().returnResult().getResponseBody();
        String json = new String(body);
        String signed = json.replaceAll(".*\"signedTransactionBase64\":\"([^\"]+)\".*", "$1");
        byte[] wire = Base64.getDecoder().decode(signed);
        byte[] message = tx.serializeMessage();
        assertThat(wire).hasSize(1 + 64 + message.length);
        assertThat(Arrays.copyOfRange(wire, 65, wire.length)).isEqualTo(message);
        assertThat(SolanaKeypair.verify(KEY.publicKeyBytes(), message, Arrays.copyOfRange(wire, 1, 65))).isTrue();
        assertThat(json).contains("\"signer\":\"" + me + "\"").contains("\"validator\":\"" + VALIDATOR.publicKeyBase58() + "\"");
        assertThat(json).doesNotContain(SigningPolicyTest.keyJson(KEY).substring(1, 20));
    }

    @Test
    void withoutTheValidatorNothingIsSigned() {
        String me = KEY.publicKeyBase58();
        LegacyTransaction tx = new LegacyTransaction(me, BLOCKHASH, List.of(SystemProgram.transfer(me, VAULT, 1234L)));
        // No attestation: the transaction is inside every byte-level limit and is still refused.
        web.post().uri("/api/signer/sign").header("X-Signer-Token", "test-token")
                .bodyValue(Map.of("proposalId", "p1", "unsignedTransactionBase64", tx.unsignedBase64(), "expectedFeePayer", me, "cluster", "devnet"))
                .exchange().expectStatus().isForbidden().expectBody().jsonPath("$.reason").isEqualTo("attestation_missing");
        // An attestation for other bytes.
        LegacyTransaction other = new LegacyTransaction(me, BLOCKHASH, List.of(SystemProgram.transfer(me, VAULT, 999L)));
        AttestationVerifier.Attestation forOther = Attestations.fresh(VALIDATOR, "p1", other.serializeMessage(), Instant.now().getEpochSecond());
        web.post().uri("/api/signer/sign").header("X-Signer-Token", "test-token")
                .bodyValue(Map.of("proposalId", "p1", "unsignedTransactionBase64", tx.unsignedBase64(), "cluster", "devnet", "attestation", attestation(forOther)))
                .exchange().expectStatus().isForbidden().expectBody().jsonPath("$.reason").isEqualTo("attestation_message_mismatch");
        // A DENY from the validator, correctly signed, for these bytes.
        String deny = Attestations.payload("p1", AttestationVerifier.sha256Hex(tx.serializeMessage()), "DENY", VALIDATOR.publicKeyBase58(),
                Instant.now().getEpochSecond(), Instant.now().getEpochSecond() + 90);
        web.post().uri("/api/signer/sign").header("X-Signer-Token", "test-token")
                .bodyValue(Map.of("proposalId", "p1", "unsignedTransactionBase64", tx.unsignedBase64(), "cluster", "devnet", "attestation", attestation(Attestations.signed(VALIDATOR, deny))))
                .exchange().expectStatus().isForbidden().expectBody().jsonPath("$.reason").isEqualTo("attestation_denied");
    }

    @Test
    void refusalsAre403WithAReason() {
        String me = KEY.publicKeyBase58();
        LegacyTransaction tx = new LegacyTransaction(me, BLOCKHASH, List.of(SystemProgram.transfer(me, VAULT, 5_000_000L)));
        // The signer's own cap is checked before the attestation: over cap is refused even with one.
        AttestationVerifier.Attestation att = Attestations.fresh(VALIDATOR, "p2", tx.serializeMessage(), Instant.now().getEpochSecond());
        web.post().uri("/api/signer/sign").header("X-Signer-Token", "test-token")
                .bodyValue(Map.of("proposalId", "p2", "unsignedTransactionBase64", tx.unsignedBase64(), "cluster", "devnet", "attestation", attestation(att)))
                .exchange().expectStatus().isForbidden().expectBody().jsonPath("$.reason").isEqualTo("amount_over_cap");
        web.post().uri("/api/signer/sign")
                .bodyValue(Map.of("proposalId", "p3", "unsignedTransactionBase64", tx.unsignedBase64()))
                .exchange().expectStatus().isUnauthorized();
    }

    @Test
    void signsAnAnchorMemoAndRefusesAMismatchedOne() {
        String me = KEY.publicKeyBase58();
        String root = "sha256:" + "ef".repeat(32);
        String memo = "ir/1 root=" + root + " n=2 ts=2026-09-18T03:00:00Z";
        LegacyTransaction tx = new LegacyTransaction(me, BLOCKHASH, List.of(new LegacyTransaction.Instruction(SigningPolicy.MEMO_PROGRAM_ID, List.of(), memo.getBytes())));
        byte[] body = web.post().uri("/api/signer/sign-anchor").header("X-Signer-Token", "test-token")
                .bodyValue(Map.of("root", root, "receiptCount", 2, "unsignedTransactionBase64", tx.unsignedBase64(), "expectedFeePayer", me))
                .exchange().expectStatus().isOk().expectBody().returnResult().getResponseBody();
        String signed = new String(body).replaceAll(".*\"signedTransactionBase64\":\"([^\"]+)\".*", "$1");
        byte[] wire = Base64.getDecoder().decode(signed);
        byte[] message = tx.serializeMessage();
        assertThat(Arrays.copyOfRange(wire, 65, wire.length)).isEqualTo(message);
        assertThat(SolanaKeypair.verify(KEY.publicKeyBytes(), message, Arrays.copyOfRange(wire, 1, 65))).isTrue();

        web.post().uri("/api/signer/sign-anchor").header("X-Signer-Token", "test-token")
                .bodyValue(Map.of("root", root, "receiptCount", 3, "unsignedTransactionBase64", tx.unsignedBase64(), "expectedFeePayer", me))
                .exchange().expectStatus().isForbidden().expectBody().jsonPath("$.reason").isEqualTo("memo_mismatch");
        web.post().uri("/api/signer/sign-anchor")
                .bodyValue(Map.of("root", root, "receiptCount", 2, "unsignedTransactionBase64", tx.unsignedBase64()))
                .exchange().expectStatus().isUnauthorized();
    }

    // ---- KAN-493: mainnet is fail-closed at the signer ---------------------------------------

    @Test
    void mainnetIsRefusedWithoutTheExplicitFlagEvenWithAValidAttestation() {
        String me = KEY.publicKeyBase58();
        LegacyTransaction tx = new LegacyTransaction(me, BLOCKHASH, List.of(SystemProgram.transfer(me, VAULT, 1234L)));
        // The validator attested these exact bytes, for mainnet-beta, ALLOW-tier, fresh — and the signer still says no.
        AttestationVerifier.Attestation att = Attestations.fresh(VALIDATOR, "p-main", tx.serializeMessage(), Instant.now().getEpochSecond(), "mainnet-beta");
        web.post().uri("/api/signer/sign").header("X-Signer-Token", "test-token")
                .bodyValue(Map.of("proposalId", "p-main", "unsignedTransactionBase64", tx.unsignedBase64(), "expectedFeePayer", me,
                        "cluster", "mainnet-beta", "attestation", attestation(att)))
                .exchange().expectStatus().isForbidden().expectBody().jsonPath("$.reason").isEqualTo("mainnet_disabled");
        // Spelling does not matter.
        web.post().uri("/api/signer/sign").header("X-Signer-Token", "test-token")
                .bodyValue(Map.of("proposalId", "p-main", "unsignedTransactionBase64", tx.unsignedBase64(), "expectedFeePayer", me,
                        "cluster", "mainnet", "attestation", attestation(att)))
                .exchange().expectStatus().isForbidden().expectBody().jsonPath("$.reason").isEqualTo("mainnet_disabled");
    }

    @Test
    void anAttestationForAnotherClusterOrARequestWithoutOneIsRefused() {
        String me = KEY.publicKeyBase58();
        LegacyTransaction tx = new LegacyTransaction(me, BLOCKHASH, List.of(SystemProgram.transfer(me, VAULT, 1234L)));
        // The request says devnet; the validator attested the bytes for mainnet-beta.
        AttestationVerifier.Attestation forMainnet = Attestations.fresh(VALIDATOR, "p1", tx.serializeMessage(), Instant.now().getEpochSecond(), "mainnet-beta");
        web.post().uri("/api/signer/sign").header("X-Signer-Token", "test-token")
                .bodyValue(Map.of("proposalId", "p1", "unsignedTransactionBase64", tx.unsignedBase64(), "expectedFeePayer", me,
                        "cluster", "devnet", "attestation", attestation(forMainnet)))
                .exchange().expectStatus().isForbidden().expectBody().jsonPath("$.reason").isEqualTo("attestation_cluster_mismatch");
        // A request that does not say where the bytes go (a pre-KAN-493 client).
        AttestationVerifier.Attestation att = Attestations.fresh(VALIDATOR, "p1", tx.serializeMessage(), Instant.now().getEpochSecond());
        web.post().uri("/api/signer/sign").header("X-Signer-Token", "test-token")
                .bodyValue(Map.of("proposalId", "p1", "unsignedTransactionBase64", tx.unsignedBase64(), "expectedFeePayer", me, "attestation", attestation(att)))
                .exchange().expectStatus().isForbidden().expectBody().jsonPath("$.reason").isEqualTo("cluster_missing");
    }
}
