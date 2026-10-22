package io.lifeengine.cryptobot.signer;

import io.lifeengine.cryptobot.signer.solana.LegacyTransaction;
import io.lifeengine.cryptobot.signer.solana.SolanaKeypair;
import io.lifeengine.cryptobot.signer.solana.SystemProgram;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * KAN-602 end to end: two transfers that each pass every per-transfer limit and carry a valid attestation; the
 * second one would take the 24 h total over {@code signer.max-lamports-per-day} and is refused {@code daily_cap}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class SignerDailyCapTest {

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
        r.add("signer.max-lamports-per-day", () -> "1500000");
        r.add("signer.validator-public-key", VALIDATOR::publicKeyBase58);
    }

    @Autowired private WebTestClient web;

    private WebTestClient.ResponseSpec sign(String proposalId, long lamports) {
        String me = KEY.publicKeyBase58();
        LegacyTransaction tx = new LegacyTransaction(me, BLOCKHASH, List.of(SystemProgram.transfer(me, VAULT, lamports)));
        AttestationVerifier.Attestation att = Attestations.fresh(VALIDATOR, proposalId, tx.serializeMessage(), Instant.now().getEpochSecond());
        return web.post().uri("/api/signer/sign").header("X-Signer-Token", "test-token")
                .bodyValue(Map.of("proposalId", proposalId, "unsignedTransactionBase64", tx.unsignedBase64(), "cluster", "devnet",
                        "attestation", Map.of("payload", att.payload(), "signature", att.signature())))
                .exchange();
    }

    @Test
    void theSecondInCapTransferThatWouldExceedTheDailyCapIsRefused() {
        sign("cap-1", 1_000_000L).expectStatus().isOk().expectBody().jsonPath("$.signature").isNotEmpty();
        sign("cap-2", 1_000_000L).expectStatus().isForbidden().expectBody().jsonPath("$.reason").isEqualTo("daily_cap");
        // what is left of the day is still signable
        sign("cap-3", 500_000L).expectStatus().isOk();
    }
}
