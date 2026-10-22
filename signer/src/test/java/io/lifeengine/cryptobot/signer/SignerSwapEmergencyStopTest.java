package io.lifeengine.cryptobot.signer;

import io.lifeengine.cryptobot.signer.solana.SolanaKeypair;
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

/** SIGNER_ENABLED=false is the emergency stop: with swaps on and a valid attestation, /sign-swap still signs nothing. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class SignerSwapEmergencyStopTest {

    static final SolanaKeypair KEY = SignerSwapEndpointTest.KEY;
    static final SolanaKeypair VALIDATOR = SignerSwapEndpointTest.VALIDATOR;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("signer.keypair-json", () -> SigningPolicyTest.keyJson(KEY));
        r.add("signer.token", () -> "test-token");
        r.add("signer.validator-public-key", VALIDATOR::publicKeyBase58);
        r.add("signer.enabled", () -> "false");
        r.add("signer.swaps.enabled", () -> "true");
        r.add("signer.swaps.min-usdc-per-sol", () -> "50000000");
        r.add("signer.swaps.min-lamports-per-usdc", () -> "4700000");
    }

    @Autowired private WebTestClient web;

    @Test
    void theEmergencyStopRefusesEvenAPerfectSwap() {
        byte[] wire = SignerSwapEndpointTest.ours();
        AttestationVerifier.Attestation att = SignerSwapEndpointTest.attest("stop", SignerSwapEndpointTest.message(wire), SignerSwapEndpointTest.BOTH);
        web.post().uri("/api/signer/sign-swap").header("X-Signer-Token", "test-token")
                .bodyValue(Map.of("proposalId", "stop", "unsignedTransactionBase64", Base64.getEncoder().encodeToString(wire), "cluster", "devnet",
                        "minOut", 1_000_000L, "attestation", Map.of("payload", att.payload(), "signature", att.signature())))
                .exchange().expectStatus().isForbidden().expectBody().jsonPath("$.reason").isEqualTo("signer_disabled");
    }
}
