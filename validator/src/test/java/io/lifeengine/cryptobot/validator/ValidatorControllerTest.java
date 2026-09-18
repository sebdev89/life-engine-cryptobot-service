package io.lifeengine.cryptobot.validator;

import static org.assertj.core.api.Assertions.assertThat;

import io.lifeengine.cryptobot.validator.crypto.Base58;
import io.lifeengine.cryptobot.validator.crypto.SolanaKeypair;
import java.nio.charset.StandardCharsets;
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
class ValidatorControllerTest {

    static final SolanaKeypair KEY = SolanaKeypair.generate();
    static final String POLICY_HASH = new PolicyStore(PolicyStoreTest.props(PolicyStoreTest.policy(""))).hash();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("validator.keypair-json", () -> ValidationServiceTest.keyJson(KEY));
        r.add("validator.token", () -> "test-token");
        r.add("validator.policy.expected-hash", () -> POLICY_HASH);
    }

    @Autowired private WebTestClient web;

    @Test
    void identityRequiresTheTokenAndReportsThePinnedPolicy() {
        web.get().uri("/api/validator/identity").exchange().expectStatus().isUnauthorized();
        web.get().uri("/api/validator/identity").header("X-Validator-Token", "wrong").exchange().expectStatus().isUnauthorized();
        web.get().uri("/api/validator/identity").header("X-Validator-Token", "test-token").exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.publicKey").isEqualTo(KEY.publicKeyBase58())
                .jsonPath("$.policyHash").isEqualTo(POLICY_HASH)
                .jsonPath("$.policyVersion").isEqualTo("cryptobot-policy-v1")
                .jsonPath("$.pinned").isEqualTo(true)
                .jsonPath("$.enabled").isEqualTo(true);
    }

    @Test
    void validatesAndTheAttestationVerifiesAgainstTheKey() {
        Map<String, Object> body = Map.of(
                "proposalId", "p-1",
                "policyHash", POLICY_HASH,
                "messageHash", "b".repeat(64),
                "intent", ValidationServiceTest.intent(),
                "state", ValidationServiceTest.state());
        ValidationService.Response r = web.post().uri("/api/validator/validate").header("X-Validator-Token", "test-token")
                .bodyValue(body).exchange().expectStatus().isOk()
                .expectBody(ValidationService.Response.class).returnResult().getResponseBody();
        assertThat(r).isNotNull();
        assertThat(r.decision()).isEqualTo("ESCALATE");
        assertThat(r.refusals()).isEmpty();
        assertThat(r.attestation().validator()).isEqualTo(KEY.publicKeyBase58());
        assertThat(SolanaKeypair.verify(KEY.publicKeyBytes(), r.attestation().payload().getBytes(StandardCharsets.UTF_8),
                Base58.decode(r.attestation().signature()))).isTrue();
        assertThat(r.attestation().payload()).contains("\"message_hash\":\"" + "b".repeat(64) + "\"");
    }

    @Test
    void refusalsAndMalformedRequests() {
        web.post().uri("/api/validator/validate")
                .bodyValue(Map.of("proposalId", "p", "policyHash", POLICY_HASH, "messageHash", "b".repeat(64)))
                .exchange().expectStatus().isUnauthorized();
        web.post().uri("/api/validator/validate").header("X-Validator-Token", "test-token")
                .bodyValue(Map.of("proposalId", "p", "policyHash", POLICY_HASH))
                .exchange().expectStatus().isBadRequest().expectBody().jsonPath("$.reason").isEqualTo("missing_or_invalid_message_hash");
        // Empty facts: every predicate unknown ⇒ DENY, and the attestation says so.
        web.post().uri("/api/validator/validate").header("X-Validator-Token", "test-token")
                .bodyValue(Map.of("proposalId", "p", "policyHash", POLICY_HASH, "messageHash", "c".repeat(64), "intent", Map.of(), "state", Map.of()))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.decision").isEqualTo("DENY")
                .jsonPath("$.failedPredicates").isEqualTo(List.of("POLICY_BOUND", "ASSET_ALLOWED", "TRADE_WITHIN_MAX", "DAILY_LIMIT", "ASSET_CONCENTRATION",
                        "SLIPPAGE_WITHIN_MAX", "ORACLE_FRESH", "AGENT_PERMITTED", "STRATEGY_ENABLED", "NONCE_UNUSED", "NOT_EXPIRED"));
    }
}
