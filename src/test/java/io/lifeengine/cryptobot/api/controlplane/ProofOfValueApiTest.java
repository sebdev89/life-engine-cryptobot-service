package io.lifeengine.cryptobot.api.controlplane;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import io.lifeengine.cryptobot.CryptobotServiceApplication;
import io.lifeengine.cryptobot.core.receipts.AnchorMemo;
import io.lifeengine.cryptobot.core.receipts.Digests;
import io.lifeengine.cryptobot.core.receipts.MerkleTree;
import io.lifeengine.cryptobot.testsupport.InMemoryControlPlaneRepositories;
import io.lifeengine.cryptobot.testsupport.InMemoryPovRepositories;
import io.lifeengine.cryptobot.testsupport.StubRepositoriesConfiguration;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.crypto.SecretKey;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * over HTTP (in-memory stores, fake devnet + fake signer that really signs, the same fakes as
 * {@link AnchorFlowTest}): identities → an accepted ValueEvent → VALUE_EVENT receipt → {@code ?anchor=true}
 * runs the admin sweep → the event reads ANCHORED → {@code /proof} correlates receiptHash ∈ tree → root in
 * the memo → tx. Plus the refusals: AcceptancePolicy 422, validation 400, unknown identity 400, anchor
 * without RUNTIME_ADMIN 403, another tenant 404.
 */
@SpringBootTest(
        classes = {CryptobotServiceApplication.class, StubRepositoriesConfiguration.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient(timeout = "30s")
@ActiveProfiles("test")
class ProofOfValueApiTest {

    static final ObjectMapper JSON = new ObjectMapper();
    static final String COMMIT = "3fb620dea17f6a7409e15337f5e074fea3097842";

    private static MockWebServer rpc;
    private static MockWebServer signer;

    @Autowired private WebTestClient web;
    @Autowired private MeterRegistry meters;

    @BeforeAll
    static void startMocks() throws Exception {
        rpc = new MockWebServer();
        rpc.setDispatcher(new AnchorFlowTest.DevnetDispatcher());
        rpc.start();
        signer = new MockWebServer();
        signer.setDispatcher(new AnchorFlowTest.SignerDispatcher());
        signer.start();
    }

    @AfterAll
    static void stopMocks() throws Exception {
        rpc.shutdown();
        signer.shutdown();
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("cryptobot.solana.rpc.devnet-url", () -> "http://localhost:" + rpc.getPort());
        r.add("cryptobot.solana.rpc.mainnet-url", () -> "http://localhost:" + rpc.getPort());
        r.add("cryptobot.signer.enabled", () -> "true");
        r.add("cryptobot.signer.base-url", () -> "http://localhost:" + signer.getPort());
        r.add("cryptobot.signer.token", () -> "flow-token");
    }

    @BeforeEach
    void reset() {
        InMemoryControlPlaneRepositories.reset();
        InMemoryPovRepositories.reset();
        AnchorFlowTest.DevnetDispatcher.reset();
    }

    @Test
    void acceptedContributionIsRecordedAnchoredAndProvable() throws Exception {
        UUID user = UUID.randomUUID();
        String admin = bearer(user, List.of("RUNTIME_OPERATOR", "RUNTIME_ADMIN"));
        seedIdentities(admin);

        // Idempotent by id: the same POST again is a 200 with the stored identity.
        web.post().uri("/api/cryptobot/identities").header(HttpHeaders.AUTHORIZATION, admin).contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"id\":\"sebas\",\"kind\":\"HUMAN\",\"displayName\":\"Otro nombre\"}")
                .exchange().expectStatus().isOk().expectBody().jsonPath("$.displayName").isEqualTo("Sebastián");
        JsonNode ids = read(web.get().uri("/api/cryptobot/identities").header(HttpHeaders.AUTHORIZATION, admin).exchange().expectStatus().isOk());
        assertThat(ids).hasSize(3);
        web.get().uri("/api/cryptobot/identities/dev-agent-17").header(HttpHeaders.AUTHORIZATION, admin).exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.kind").isEqualTo("AGENT").jsonPath("$.ownerId").isEqualTo("sebas");

        // Recorded without anchoring first: RECORDED, units split 34/33/33, hashes are sha256 of the canonical trees.
        JsonNode recorded = read(post(admin, "/api/cryptobot/value-events", event(true)).expectStatus().isCreated());
        assertThat(recorded.path("status").asText()).isEqualTo("RECORDED");
        assertThat(recorded.path("anchor").isNull()).isTrue();
        assertThat(recorded.path("totalUnits").asInt()).isEqualTo(100);
        assertThat(recorded.path("distributionPolicy").asText()).isEqualTo("pov/equal-split/v1");
        List<Integer> units = new ArrayList<>();
        recorded.path("contributions").forEach(c -> units.add(c.path("units").asInt()));
        assertThat(units).containsExactly(34, 33, 33);
        assertThat(recorded.path("contributions").get(1).path("displayName").asText()).isEqualTo("Dev Agent 17");
        assertThat(recorded.path("contributions").get(1).path("kind").asText()).isEqualTo("AGENT");
        assertThat(recorded.path("artifact").path("commitSha").asText()).isEqualTo(COMMIT);
        assertThat(recorded.path("artifact").path("prUrl").asText()).isEqualTo("https://github.com/sebdev89/life-engine-cryptobot-service/pull/48");
        assertThat(recorded.path("acceptance").path("stages").path("ACCEPTED").asBoolean()).isTrue();
        assertThat(recorded.path("acceptance").path("environment").asText()).isEqualTo("uat-k8s");
        assertThat(recorded.path("artifactHash").asText()).matches("^sha256:[0-9a-f]{64}$");
        String id = recorded.path("id").asText();
        String receiptHash = recorded.path("receiptHash").asText();

        // The same content again: 200, the same event (no second receipt).
        JsonNode again = read(post(admin, "/api/cryptobot/value-events", event(true)).expectStatus().isOk());
        assertThat(again.path("id").asText()).isEqualTo(id);
        assertThat(InMemoryControlPlaneRepositories.RECEIPTS).hasSize(1);

        // The receipt is a VALUE_EVENT whose output hash is the event hash.
        JsonNode receipt = read(web.get().uri("/api/cryptobot/receipts/" + receiptHash).header(HttpHeaders.AUTHORIZATION, admin).exchange().expectStatus().isOk());
        assertThat(receipt.path("receipt").path("body").path("kind").asText()).isEqualTo("VALUE_EVENT");
        assertThat(receipt.path("receipt").path("body").path("output").path("hash").asText()).isEqualTo(recorded.path("valueEventHash").asText());

        // An operator cannot anchor; an admin anchors now (same sweep as POST /anchors?wait=true).
        String operator = bearer(user, List.of("RUNTIME_OPERATOR"));
        post(operator, "/api/cryptobot/value-events?anchor=true", event(true)).expectStatus().isForbidden();
        JsonNode anchored = read(post(admin, "/api/cryptobot/value-events?anchor=true", event(true)).expectStatus().isOk());
        assertThat(anchored.path("status").asText()).isEqualTo("ANCHORED");
        assertThat(anchored.path("anchorStatus").asText()).isEqualTo("FINALIZED");
        String root = anchored.path("anchor").path("root").asText();
        String tx = anchored.path("anchor").path("txSignature").asText();
        assertThat(anchored.path("anchor").path("slot").asLong()).isEqualTo(4242L);
        assertThat(tx).isEqualTo(AnchorFlowTest.DevnetDispatcher.lastSignature.get());
        // The RPC of this test is localhost: the explorer link is the custom-cluster one, origin only.
        assertThat(anchored.path("anchor").path("explorerUrl").asText())
                .isEqualTo("https://explorer.solana.com/tx/" + tx + "?cluster=custom&customUrl=http%3A%2F%2Flocalhost%3A" + rpc.getPort());
        AnchorMemo memo = AnchorMemo.parse(AnchorFlowTest.DevnetDispatcher.lastMemo.get()).orElseThrow();
        assertThat(memo.root()).isEqualTo(root);
        assertThat(MerkleTree.of(List.of(receiptHash)).root()).isEqualTo(root);

        // GET reads the anchor from the receipt; the list is newest first.
        web.get().uri("/api/cryptobot/value-events/" + id).header(HttpHeaders.AUTHORIZATION, admin).exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.status").isEqualTo("ANCHORED").jsonPath("$.anchor.root").isEqualTo(root)
                .jsonPath("$.taskId").isEqualTo("TASK-818").jsonPath("$.acceptance.source").isEqualTo("release-truth");
        JsonNode list = read(web.get().uri("/api/cryptobot/value-events?limit=5").header(HttpHeaders.AUTHORIZATION, admin).exchange().expectStatus().isOk());
        assertThat(list).hasSize(1);
        assertThat(list.get(0).path("id").asText()).isEqualTo(id);

        // The proof: receipt checks + inclusion + the event hash recomputed, all true.
        JsonNode proof = read(web.get().uri("/api/cryptobot/value-events/" + id + "/proof").header(HttpHeaders.AUTHORIZATION, admin).exchange().expectStatus().isOk());
        assertThat(proof.path("verified").asBoolean()).isTrue();
        assertThat(proof.path("valid").asBoolean()).isTrue();
        assertThat(proof.path("receiptHash").asText()).isEqualTo(receiptHash);
        assertThat(proof.path("root").asText()).isEqualTo(root);
        assertThat(proof.path("txSignature").asText()).isEqualTo(tx);
        assertThat(proof.path("valueEventHashValid").asBoolean()).isTrue();
        assertThat(proof.path("anchor").path("proofValid").asBoolean()).isTrue();

        // Another tenant sees nothing.
        String other = bearer(UUID.randomUUID(), List.of("RUNTIME_OPERATOR"));
        web.get().uri("/api/cryptobot/value-events/" + id).header(HttpHeaders.AUTHORIZATION, other).exchange().expectStatus().isNotFound();
        web.get().uri("/api/cryptobot/identities/sebas").header(HttpHeaders.AUTHORIZATION, other).exchange().expectStatus().isNotFound();
        assertThat(read(web.get().uri("/api/cryptobot/value-events").header(HttpHeaders.AUTHORIZATION, other).exchange().expectStatus().isOk())).isEmpty();

        assertThat(meters.find("pov.value.events").tag("status", "recorded").counter().count()).isGreaterThanOrEqualTo(1.0);
        assertThat(meters.find("pov.value.events").tag("status", "anchored").counter().count()).isGreaterThanOrEqualTo(1.0);
        assertThat(meters.find("pov.identities").counter().count()).isGreaterThanOrEqualTo(3.0);
    }

    @Test
    void refusals() throws Exception {
        String admin = bearer(UUID.randomUUID(), List.of("RUNTIME_OPERATOR", "RUNTIME_ADMIN"));
        seedIdentities(admin);

        // AcceptancePolicy V1: RUNNING false and ACCEPTED missing → 422 with one detail per stage, nothing written.
        String notAccepted = event(true).replace("\"RUNNING\":true,\"ACCEPTED\":true", "\"RUNNING\":false");
        post(admin, "/api/cryptobot/value-events", notAccepted).expectStatus().isEqualTo(422)
                .expectBody().jsonPath("$.code").isEqualTo("ACCEPTANCE_POLICY_REJECTED")
                .jsonPath("$.details[0]").isEqualTo("RUNNING: false").jsonPath("$.details[1]").isEqualTo("ACCEPTED: missing");
        assertThat(InMemoryControlPlaneRepositories.RECEIPTS).isEmpty();

        // @Valid: no contributions, a bad commit → 400 in the ApiError shape.
        String invalid = event(true).replace(COMMIT, "not-a-sha").replaceAll("\"contributions\":\\[[^\\]]*\\]", "\"contributions\":[]");
        post(admin, "/api/cryptobot/value-events", invalid).expectStatus().isBadRequest()
                .expectBody().jsonPath("$.code").isEqualTo("INVALID_REQUEST").jsonPath("$.details.length()").isEqualTo(2);
        // An unknown role is an unreadable body: 400, same shape.
        post(admin, "/api/cryptobot/value-events", event(true).replace("IMPLEMENTER", "WIZARD")).expectStatus().isBadRequest()
                .expectBody().jsonPath("$.code").isEqualTo("INVALID_REQUEST");
        // An identity that is not registered: 400 UNKNOWN_IDENTITY.
        post(admin, "/api/cryptobot/value-events", event(true).replace("cryptobot-001", "ghost")).expectStatus().isBadRequest()
                .expectBody().jsonPath("$.code").isEqualTo("UNKNOWN_IDENTITY");
        // Another distribution policy: 400.
        post(admin, "/api/cryptobot/value-events", event(true).replace("\"distributionPolicy\":\"pov/equal-split/v1\"", "\"distributionPolicy\":\"pov/ai-decides/v1\""))
                .expectStatus().isBadRequest().expectBody().jsonPath("$.code").isEqualTo("UNSUPPORTED_DISTRIBUTION_POLICY");
        // An agent whose owner does not exist.
        web.post().uri("/api/cryptobot/identities").header(HttpHeaders.AUTHORIZATION, admin).contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"id\":\"orphan\",\"kind\":\"AGENT\",\"displayName\":\"Orphan\",\"wallet\":\"GwMtp15arkyoxkJ6ah3R6hCab3SXCJhsSWsZ2DyTeVnW\",\"ownerId\":\"nobody\"}")
                .exchange().expectStatus().isBadRequest().expectBody().jsonPath("$.code").isEqualTo("UNKNOWN_IDENTITY");
        web.get().uri("/api/cryptobot/value-events/" + UUID.randomUUID()).header(HttpHeaders.AUTHORIZATION, admin).exchange().expectStatus().isNotFound()
                .expectBody().jsonPath("$.code").isEqualTo("NOT_FOUND");
        web.get().uri("/api/cryptobot/value-events").exchange().expectStatus().isUnauthorized();
        assertThat(InMemoryControlPlaneRepositories.RECEIPTS).isEmpty();
        assertThat(InMemoryPovRepositories.EVENTS).isEmpty();
        assertThat(Digests.isHash("sha256:" + "0".repeat(64))).isTrue();
    }

    private void seedIdentities(String token) throws Exception {
        for (String body : List.of(
                "{\"id\":\"sebas\",\"kind\":\"HUMAN\",\"displayName\":\"Sebastián\"}",
                "{\"id\":\"dev-agent-17\",\"kind\":\"AGENT\",\"displayName\":\"Dev Agent 17\",\"wallet\":\"GwMtp15arkyoxkJ6ah3R6hCab3SXCJhsSWsZ2DyTeVnW\",\"ownerId\":\"sebas\"}",
                "{\"id\":\"cryptobot-001\",\"kind\":\"AGENT\",\"displayName\":\"CryptoBot 001\",\"wallet\":\"Cm48Eg67MfPpkpS5SKngrA587nHNgDgXjHLwfY9U81e7\",\"ownerId\":\"sebas\",\"operatorId\":\"sebas\"}")) {
            web.post().uri("/api/cryptobot/identities").header(HttpHeaders.AUTHORIZATION, token).contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body).exchange().expectStatus().isCreated();
        }
    }

    static String event(boolean accepted) {
        return "{\"projectId\":\"cryptobot\",\"taskId\":\"TASK-818\",\"title\":\"Improve CryptoBot opportunity detection\","
                + "\"artifact\":{\"commitSha\":\"" + COMMIT + "\",\"prUrl\":\"https://github.com/sebdev89/life-engine-cryptobot-service/pull/48\"},"
                + "\"acceptance\":{\"source\":\"release-truth\",\"environment\":\"uat-k8s\",\"stages\":{\"MERGED\":true,\"BUILT\":true,\"DEPLOYED\":true,"
                + "\"RUNNING\":true,\"ACCEPTED\":" + accepted + "},\"evidenceRef\":\"release-truth uat cryptobot --json\",\"acceptedAt\":\"2026-09-30T10:00:00Z\"},"
                + "\"contributions\":[{\"identityId\":\"sebas\",\"role\":\"SPECIFIER\"},{\"identityId\":\"dev-agent-17\",\"role\":\"IMPLEMENTER\"},"
                + "{\"identityId\":\"cryptobot-001\",\"role\":\"OPERATOR\"}],\"distributionPolicy\":\"pov/equal-split/v1\"}";
    }

    private WebTestClient.ResponseSpec post(String token, String uri, String body) {
        return web.post().uri(uri).header(HttpHeaders.AUTHORIZATION, token).contentType(MediaType.APPLICATION_JSON).bodyValue(body).exchange();
    }

    private static JsonNode read(WebTestClient.ResponseSpec spec) throws Exception {
        return JSON.readTree(spec.expectBody().returnResult().getResponseBody());
    }

    static String bearer(UUID userId, List<String> authorities) {
        SecretKey key = Keys.hmacShaKeyFor("test-jwt-secret-at-least-32-bytes-long!!".getBytes(StandardCharsets.UTF_8));
        return "Bearer " + Jwts.builder()
                .subject(userId.toString())
                .claim("email", "operator@test.local")
                .claim("authorities", authorities)
                .issuedAt(java.util.Date.from(Instant.now()))
                .expiration(java.util.Date.from(Instant.now().plusSeconds(300)))
                .signWith(key)
                .compact();
    }
}
