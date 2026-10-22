package io.lifeengine.cryptobot.signer;

import io.lifeengine.cryptobot.signer.solana.SolanaKeypair;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

/** Swaps are off unless gitops turns them on: with the default configuration /sign-swap signs nothing. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class SignerSwapDisabledTest {

    static final SolanaKeypair KEY = SolanaKeypair.generate();
    static final SolanaKeypair VALIDATOR = SolanaKeypair.generate();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("signer.keypair-json", () -> SigningPolicyTest.keyJson(KEY));
        r.add("signer.token", () -> "test-token");
        r.add("signer.validator-public-key", VALIDATOR::publicKeyBase58);
        // no signer.swaps.enabled: the default
    }

    @Autowired private WebTestClient web;

    @Test
    void byDefaultASwapIsRefusedSwapsDisabled() {
        byte[] wire = SwapSigningPolicyTest.solToUsdc();
        web.post().uri("/api/signer/sign-swap").header("X-Signer-Token", "test-token")
                .bodyValue(Map.of("proposalId", "p", "unsignedTransactionBase64", Base64.getEncoder().encodeToString(wire), "cluster", "devnet"))
                .exchange().expectStatus().isForbidden().expectBody().jsonPath("$.reason").isEqualTo("swaps_disabled");
        web.post().uri("/api/signer/sign-swap")
                .bodyValue(Map.of("proposalId", "p", "unsignedTransactionBase64", Base64.getEncoder().encodeToString(wire), "cluster", "devnet"))
                .exchange().expectStatus().isUnauthorized();
    }
}
