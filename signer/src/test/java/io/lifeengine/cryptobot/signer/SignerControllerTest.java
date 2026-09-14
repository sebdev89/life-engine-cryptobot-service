package io.lifeengine.cryptobot.signer;

import static org.assertj.core.api.Assertions.assertThat;

import io.lifeengine.cryptobot.signer.solana.LegacyTransaction;
import io.lifeengine.cryptobot.signer.solana.SolanaKeypair;
import io.lifeengine.cryptobot.signer.solana.SystemProgram;
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
    static final String VAULT = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v";
    static final String BLOCKHASH = "So11111111111111111111111111111111111111112";

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("signer.keypair-json", () -> SigningPolicyTest.keyJson(KEY));
        r.add("signer.token", () -> "test-token");
        r.add("signer.allowed-destinations", () -> VAULT);
        r.add("signer.max-lamports", () -> "1000000");
    }

    @Autowired private WebTestClient web;

    @Test
    void identityRequiresTheToken() {
        web.get().uri("/api/signer/identity").exchange().expectStatus().isUnauthorized();
        web.get().uri("/api/signer/identity").header("X-Signer-Token", "wrong").exchange().expectStatus().isUnauthorized();
        web.get().uri("/api/signer/identity").header("X-Signer-Token", "test-token").exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.publicKey").isEqualTo(KEY.publicKeyBase58()).jsonPath("$.maxLamports").isEqualTo(1000000);
    }

    @Test
    void signsAndTheSignatureVerifiesAgainstTheKey() {
        String me = KEY.publicKeyBase58();
        LegacyTransaction tx = new LegacyTransaction(me, BLOCKHASH, List.of(SystemProgram.transfer(me, VAULT, 1234L)));
        byte[] body = web.post().uri("/api/signer/sign").header("X-Signer-Token", "test-token")
                .bodyValue(Map.of("proposalId", "p1", "unsignedTransactionBase64", tx.unsignedBase64(), "expectedFeePayer", me))
                .exchange().expectStatus().isOk().expectBody().returnResult().getResponseBody();
        String json = new String(body);
        String signed = json.replaceAll(".*\"signedTransactionBase64\":\"([^\"]+)\".*", "$1");
        byte[] wire = Base64.getDecoder().decode(signed);
        byte[] message = tx.serializeMessage();
        assertThat(wire).hasSize(1 + 64 + message.length);
        assertThat(Arrays.copyOfRange(wire, 65, wire.length)).isEqualTo(message);
        assertThat(SolanaKeypair.verify(KEY.publicKeyBytes(), message, Arrays.copyOfRange(wire, 1, 65))).isTrue();
        assertThat(json).contains("\"signer\":\"" + me + "\"");
        assertThat(json).doesNotContain(SigningPolicyTest.keyJson(KEY).substring(1, 20));
    }

    @Test
    void refusalsAre403WithAReason() {
        String me = KEY.publicKeyBase58();
        LegacyTransaction tx = new LegacyTransaction(me, BLOCKHASH, List.of(SystemProgram.transfer(me, VAULT, 5_000_000L)));
        web.post().uri("/api/signer/sign").header("X-Signer-Token", "test-token")
                .bodyValue(Map.of("proposalId", "p2", "unsignedTransactionBase64", tx.unsignedBase64()))
                .exchange().expectStatus().isForbidden().expectBody().jsonPath("$.reason").isEqualTo("amount_over_cap");
        web.post().uri("/api/signer/sign")
                .bodyValue(Map.of("proposalId", "p3", "unsignedTransactionBase64", tx.unsignedBase64()))
                .exchange().expectStatus().isUnauthorized();
    }
}
