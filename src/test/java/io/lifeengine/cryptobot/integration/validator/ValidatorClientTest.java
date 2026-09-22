package io.lifeengine.cryptobot.integration.validator;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.lifeengine.cryptobot.domain.policy.DeterministicPolicyEngine;
import io.lifeengine.cryptobot.domain.policy.PolicyDecision;
import io.lifeengine.cryptobot.domain.policy.PolicyInput;
import io.lifeengine.cryptobot.domain.policy.PolicyRules;
import io.lifeengine.cryptobot.domain.policy.PolicyVerdict;
import io.lifeengine.cryptobot.domain.transactions.ActionProposal;
import io.lifeengine.cryptobot.domain.transactions.PreparedTransaction;
import io.lifeengine.cryptobot.domain.transactions.ProposalStatus;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.test.StepVerifier;

/** KAN-438: what the service sends the validator, and which answers it refuses to act on. */
class ValidatorClientTest {

    private static MockWebServer server;
    private static ValidatorClient client;
    private static final Instant NOW = Instant.parse("2026-09-18T12:00:00Z");
    private static final byte[] MESSAGE = "fake-solana-message".getBytes(StandardCharsets.UTF_8);
    private static final PolicyRules RULES = new PolicyRules("cryptobot-policy-v1", List.of("SOL", "USDC"), List.of("REBALANCE"),
            50_000, 250_000, 8_000, 100, 900, 10_000, 25_000);

    @BeforeAll
    static void start() throws Exception {
        server = new MockWebServer();
        server.start();
        client = new ValidatorClient(WebClient.builder(), new ValidatorProperties(true, "http://localhost:" + server.getPort(), "tok", Duration.ofSeconds(2)));
    }

    @AfterAll
    static void stop() throws Exception {
        server.shutdown();
    }

    static PolicyInput input() {
        return new PolicyInput(
                new PolicyInput.IntentFacts("user-1", "REBALANCE", "cryptobot-policy-v1", "SOL", 15_000L, 50, NOW.getEpochSecond() + 600),
                new PolicyInput.StateFacts(0L, 5_000, 30L, true, true, NOW.getEpochSecond()));
    }

    static ActionProposal proposal(PolicyDecision decision) {
        return new ActionProposal(UUID.fromString("00000000-0000-0000-0000-000000000001"), UUID.randomUUID(), UUID.randomUUID(), "wallet", "devnet",
                ProposalStatus.EXECUTING, "REBALANCE", "t", null, "op", null, null, null, null, decision, null, tx(), null, null, null, null,
                NOW.plusSeconds(600), NOW, NOW, null, 0);
    }

    static PreparedTransaction tx() {
        String b64 = Base64.getEncoder().encodeToString(MESSAGE);
        return new PreparedTransaction("devnet", "wallet", "vault", 1L, "bh", 1L, b64, b64, "transfer");
    }

    static String messageHash() throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(MESSAGE));
    }

    static String response(String decision, String policyHash, String verdictHash, String messageHash) {
        return response(decision, policyHash, verdictHash, messageHash, "devnet");
    }

    static String response(String decision, String policyHash, String verdictHash, String messageHash, String cluster) {
        String payload = "{\\\"cluster\\\":\\\"" + cluster + "\\\",\\\"decision\\\":\\\"" + decision + "\\\",\\\"message_hash\\\":\\\"" + messageHash + "\\\"}";
        return "{\"decision\":\"" + decision + "\",\"escalation\":\"REQUIRE_SECOND_AGENT\",\"tier\":\"SECOND_AGENT\",\"failedPredicates\":[],\"refusals\":[],"
                + "\"policyVersion\":\"cryptobot-policy-v1\",\"policyHash\":\"" + policyHash + "\",\"inputHash\":\"x\",\"verdictHash\":\"" + verdictHash + "\","
                + "\"issuedAt\":" + NOW.getEpochSecond() + ",\"expiresAt\":" + (NOW.getEpochSecond() + 90) + ","
                + "\"attestation\":{\"payload\":\"" + payload + "\",\"signature\":\"sig\",\"validator\":\"vkey\"}}";
    }

    @Test
    void sendsTheRecordedFactsHashesAndMessageHashAndAcceptsAgreement() throws Exception {
        PolicyInput in = input();
        PolicyVerdict verdict = DeterministicPolicyEngine.evaluate(RULES, in);
        PolicyDecision decision = new PolicyDecision(true, true, List.of(), List.of(), List.of(), NOW, verdict, in);
        server.enqueue(json(response("ESCALATE", verdict.policyHash(), verdict.hash(), messageHash())));

        StepVerifier.create(client.authorize(proposal(decision), tx()))
                .assertNext(r -> {
                    assertThat(r.decision()).isEqualTo("ESCALATE");
                    assertThat(r.attestation().signature()).isEqualTo("sig");
                    assertThat(r.expires()).isEqualTo(NOW.plusSeconds(90));
                })
                .verifyComplete();

        RecordedRequest req = server.takeRequest();
        assertThat(req.getPath()).isEqualTo("/api/validator/validate");
        assertThat(req.getHeader("X-Validator-Token")).isEqualTo("tok");
        JsonNode body = new ObjectMapper().readTree(req.getBody().readUtf8());
        assertThat(body.path("proposalId").asText()).isEqualTo("00000000-0000-0000-0000-000000000001");
        assertThat(body.path("policyHash").asText()).isEqualTo(verdict.policyHash());
        assertThat(body.path("expectedVerdictHash").asText()).isEqualTo(verdict.hash());
        assertThat(body.path("messageHash").asText()).isEqualTo(messageHash());
        // KAN-493: the cluster the bytes are for travels with the request and comes back attested.
        assertThat(body.path("cluster").asText()).isEqualTo("devnet");
        // The facts travel in the schema's snake_case, exactly as they were hashed.
        assertThat(body.path("intent").path("trade_value_cents").asLong()).isEqualTo(15_000L);
        assertThat(body.path("intent").path("policy_version").asText()).isEqualTo("cryptobot-policy-v1");
        assertThat(body.path("state").path("agent_permitted").asBoolean()).isTrue();
        assertThat(body.path("state").path("current_slot").asLong()).isEqualTo(NOW.getEpochSecond());
    }

    @Test
    void refusesDenyDisagreementOtherPolicyAndOtherBytes() throws Exception {
        PolicyInput in = input();
        PolicyVerdict verdict = DeterministicPolicyEngine.evaluate(RULES, in);
        PolicyDecision decision = new PolicyDecision(true, true, List.of(), List.of(), List.of(), NOW, verdict, in);

        server.enqueue(json(response("DENY", verdict.policyHash(), verdict.hash(), messageHash())));
        StepVerifier.create(client.authorize(proposal(decision), tx()))
                .expectErrorSatisfies(ex -> assertThat(ex).isInstanceOf(ValidatorClient.ValidatorRefused.class).hasMessageContaining("decision DENY"))
                .verify();

        server.enqueue(json(response("ESCALATE", verdict.policyHash(), "sha256:" + "9".repeat(64), messageHash())));
        StepVerifier.create(client.authorize(proposal(decision), tx()))
                .expectErrorSatisfies(ex -> assertThat(ex).hasMessageContaining("verdict disagreement"))
                .verify();

        server.enqueue(json(response("ESCALATE", "sha256:" + "8".repeat(64), verdict.hash(), messageHash())));
        StepVerifier.create(client.authorize(proposal(decision), tx()))
                .expectErrorSatisfies(ex -> assertThat(ex).hasMessageContaining("policy hash disagreement"))
                .verify();

        server.enqueue(json(response("ESCALATE", verdict.policyHash(), verdict.hash(), "f".repeat(64))));
        StepVerifier.create(client.authorize(proposal(decision), tx()))
                .expectErrorSatisfies(ex -> assertThat(ex).hasMessageContaining("not for these transaction bytes"))
                .verify();

        // KAN-493: an attestation for another cluster (or none) is not an attestation for these bytes.
        server.enqueue(json(response("ESCALATE", verdict.policyHash(), verdict.hash(), messageHash(), "mainnet-beta")));
        StepVerifier.create(client.authorize(proposal(decision), tx()))
                .expectErrorSatisfies(ex -> assertThat(ex).hasMessageContaining("not for cluster devnet"))
                .verify();

        server.enqueue(new MockResponse().setResponseCode(503));
        StepVerifier.create(client.authorize(proposal(decision), tx()))
                .expectErrorSatisfies(ex -> assertThat(ex).isInstanceOf(ValidatorClient.ValidatorRefused.class).hasMessageContaining("HTTP 503"))
                .verify();
    }

    /**
     * KAN-500: found by the chain E2E with the validator "down". A 200 with no body completed
     * {@code authorize} <em>empty</em>: the execution pipeline skipped the validator and the signer
     * and answered HTTP 200 with no proposal, leaving the row EXECUTING with nothing recorded. No
     * answer is a refusal — never an empty completion. A dropped connection is a refusal too.
     */
    @Test
    void noAnswerIsARefusalNeverAnEmptyCompletion() {
        PolicyInput in = input();
        PolicyVerdict verdict = DeterministicPolicyEngine.evaluate(RULES, in);
        PolicyDecision decision = new PolicyDecision(true, true, List.of(), List.of(), List.of(), NOW, verdict, in);

        server.enqueue(new MockResponse().setResponseCode(200));
        StepVerifier.create(client.authorize(proposal(decision), tx()))
                .expectErrorSatisfies(ex -> assertThat(ex).isInstanceOf(ValidatorClient.ValidatorRefused.class).hasMessageContaining("no answer"))
                .verify();

        server.enqueue(new MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AT_START));
        StepVerifier.create(client.authorize(proposal(decision), tx()))
                .expectErrorSatisfies(ex -> assertThat(ex).isInstanceOf(ValidatorClient.ValidatorRefused.class))
                .verify();
    }

    @Test
    void refusesWithoutRecordedFactsOrWhenDisabled() {
        PolicyVerdict verdict = DeterministicPolicyEngine.evaluate(RULES, input());
        PolicyDecision legacy = new PolicyDecision(true, true, List.of(), List.of(), List.of(), NOW, verdict);
        StepVerifier.create(client.authorize(proposal(legacy), tx()))
                .expectErrorSatisfies(ex -> assertThat(ex).hasMessageContaining("no recorded (I, S)"))
                .verify();

        ValidatorClient disabled = new ValidatorClient(WebClient.builder(), new ValidatorProperties(false, "http://localhost:1", "t", Duration.ofSeconds(1)));
        StepVerifier.create(disabled.authorize(proposal(legacy), tx()))
                .expectErrorSatisfies(ex -> assertThat(ex).hasMessageContaining("validator disabled"))
                .verify();
        StepVerifier.create(disabled.identity()).assertNext(id -> assertThat(id).isEmpty()).verifyComplete();
    }

    @Test
    void identityIsEmptyWhenTheValidatorDoesNotAnswer() {
        server.enqueue(new MockResponse().setResponseCode(401).setBody("{\"reason\":\"bad_token\"}").addHeader("Content-Type", "application/json"));
        StepVerifier.create(client.identity()).assertNext(id -> assertThat(id).isEmpty()).verifyComplete();

        server.enqueue(json("{\"publicKey\":\"vkey\",\"policyVersion\":\"cryptobot-policy-v1\",\"policyHash\":\"sha256:ab\",\"pinned\":true,\"enabled\":true,\"attestationTtlSeconds\":90}"));
        StepVerifier.create(client.identity()).assertNext(id -> {
            assertThat(id).isPresent();
            assertThat(id.get().policyHash()).isEqualTo("sha256:ab");
            assertThat(id.get().pinned()).isTrue();
        }).verifyComplete();
    }

    private static MockResponse json(String body) {
        return new MockResponse().setBody(body).addHeader("Content-Type", "application/json");
    }
}
