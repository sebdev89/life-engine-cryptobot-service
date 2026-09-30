package io.lifeengine.cryptobot.api.controlplane;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.lifeengine.cryptobot.CryptobotServiceApplication;
import io.lifeengine.cryptobot.core.receipts.Digests;
import io.lifeengine.cryptobot.proofofvalue.IdentityKind;
import io.lifeengine.cryptobot.proofofvalue.PovIdentity;
import io.lifeengine.cryptobot.testsupport.InMemoryControlPlaneRepositories;
import io.lifeengine.cryptobot.testsupport.InMemoryPovRepositories;
import io.lifeengine.cryptobot.testsupport.StubRepositoriesConfiguration;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
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
 * KAN-819 over HTTP (in-memory stores, the fakes of {@link AnchorFlowTest}): V2 identities with wallet, reputation and
 * history · V3 knowledge assets with {@code usedIn} and the KNOWLEDGE_PROVIDER added for an asset's creator · V4
 * compute receipts committed in the event, apart from the units · V6 the ledger by identity / asset / project, whose
 * rows add up to every unit distributed. Plus the 422s of each rule.
 */
@SpringBootTest(
        classes = {CryptobotServiceApplication.class, StubRepositoriesConfiguration.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient(timeout = "30s")
@ActiveProfiles("test")
class ProofOfValueAttributionApiTest {

    static final ObjectMapper JSON = new ObjectMapper();
    static final String W_DEV = "GwMtp15arkyoxkJ6ah3R6hCab3SXCJhsSWsZ2DyTeVnW";
    static final String W_BOT = "Cm48Eg67MfPpkpS5SKngrA587nHNgDgXjHLwfY9U81e7";
    static final String W_REVIEW = "9TjDYLcyZaBFMzqcJxg1bWdDwvA9cZZYoSm7zvjMqdP8";
    static final String W_COMPUTE = "5ps1ihy1rNd7JeRkkxW1YU2PE3y47rbrb6DxxdnAbmHx";
    /** Valid Base58 that decodes to 31 bytes: not a public key. */
    static final String W_SHORT = "hBxVhPQ8E4i2LegsKLvezqUWNt1atk4gw3hJohmLKh";
    static final String RULES_HASH = Digests.sha256("MERGED→BUILT→DEPLOYED→RUNNING→ACCEPTED");
    static final String STRATEGY_HASH = Digests.sha256("strategy-knowledge v3");

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
    void identityWalletRules() throws Exception {
        UUID user = UUID.randomUUID();
        String admin = ProofOfValueApiTest.bearer(user, List.of("RUNTIME_OPERATOR", "RUNTIME_ADMIN"));
        post(admin, "/api/cryptobot/identities", "{\"id\":\"sebas\",\"kind\":\"HUMAN\",\"displayName\":\"Sebastián\"}").expectStatus().isCreated()
                .expectBody().jsonPath("$.wallet").doesNotExist();
        post(admin, "/api/cryptobot/identities", "{\"id\":\"no-wallet\",\"kind\":\"AGENT\",\"displayName\":\"No wallet\",\"ownerId\":\"sebas\"}")
                .expectStatus().isEqualTo(422).expectBody().jsonPath("$.code").isEqualTo("AGENT_WALLET_REQUIRED");
        post(admin, "/api/cryptobot/identities", "{\"id\":\"short\",\"kind\":\"AGENT\",\"displayName\":\"Short\",\"wallet\":\"" + W_SHORT + "\"}")
                .expectStatus().isEqualTo(422).expectBody().jsonPath("$.code").isEqualTo("INVALID_WALLET");
        // Not Base58 at all ('0' is outside the alphabet): the DTO pattern, 400.
        post(admin, "/api/cryptobot/identities", "{\"id\":\"zero\",\"kind\":\"AGENT\",\"displayName\":\"Zero\",\"wallet\":\"" + "0".repeat(44) + "\"}")
                .expectStatus().isBadRequest();
        post(admin, "/api/cryptobot/identities", "{\"id\":\"dev-agent-17\",\"kind\":\"AGENT\",\"displayName\":\"Dev Agent 17\",\"wallet\":\"" + W_DEV
                + "\",\"ownerId\":\"sebas\"}").expectStatus().isCreated().expectBody().jsonPath("$.wallet").isEqualTo(W_DEV);
        // A stored wallet is never overwritten by a re-POST.
        post(admin, "/api/cryptobot/identities", "{\"id\":\"dev-agent-17\",\"kind\":\"AGENT\",\"displayName\":\"Dev Agent 17\",\"wallet\":\"" + W_BOT
                + "\",\"ownerId\":\"sebas\"}").expectStatus().isOk().expectBody().jsonPath("$.wallet").isEqualTo(W_DEV);

        // A V1 agent stored without a wallet stays readable, and gets its wallet once when posted again with one.
        String tenant = user.toString();
        InMemoryPovRepositories.IDENTITIES.put(tenant + "|legacy-agent",
                new PovIdentity(tenant, "legacy-agent", IdentityKind.AGENT, "Legacy", null, "sebas", null, Instant.now()));
        web.get().uri("/api/cryptobot/identities/legacy-agent").header(HttpHeaders.AUTHORIZATION, admin).exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.wallet").doesNotExist();
        post(admin, "/api/cryptobot/identities", "{\"id\":\"legacy-agent\",\"kind\":\"AGENT\",\"displayName\":\"Legacy\"}").expectStatus().isOk();
        post(admin, "/api/cryptobot/identities", "{\"id\":\"legacy-agent\",\"kind\":\"AGENT\",\"displayName\":\"Legacy\",\"wallet\":\"" + W_REVIEW + "\"}")
                .expectStatus().isOk().expectBody().jsonPath("$.wallet").isEqualTo(W_REVIEW);
    }

    @Test
    void knowledgeComputeReputationAndLedger() throws Exception {
        UUID user = UUID.randomUUID();
        String admin = ProofOfValueApiTest.bearer(user, List.of("RUNTIME_OPERATOR", "RUNTIME_ADMIN"));
        seed(admin);

        // V3: assets, idempotent by id; creator and parents must exist (422).
        JsonNode rules = read(post(admin, "/api/cryptobot/knowledge-assets", asset("production-acceptance-model@1", 1, "RULESET",
                "Production acceptance model", "sebas", RULES_HASH, "[]")).expectStatus().isCreated());
        assertThat(rules.path("creatorDisplayName").asText()).isEqualTo("Sebastián");
        assertThat(rules.path("usedIn")).isEmpty();
        post(admin, "/api/cryptobot/knowledge-assets", asset("production-acceptance-model@1", 1, "RULESET", "Other title", "sebas", RULES_HASH, "[]"))
                .expectStatus().isOk().expectBody().jsonPath("$.title").isEqualTo("Production acceptance model");
        post(admin, "/api/cryptobot/knowledge-assets", asset("strategy-knowledge@3", 3, "STRATEGY", "Strategy knowledge", "sebas", STRATEGY_HASH,
                "[\"production-acceptance-model@1\"]")).expectStatus().isCreated().expectBody().jsonPath("$.parentIds[0]").isEqualTo("production-acceptance-model@1");
        post(admin, "/api/cryptobot/knowledge-assets", asset("ghost-asset@1", 1, "PROMPT", "Ghost", "nobody", RULES_HASH, "[]"))
                .expectStatus().isEqualTo(422).expectBody().jsonPath("$.code").isEqualTo("UNKNOWN_IDENTITY");
        post(admin, "/api/cryptobot/knowledge-assets", asset("child@1", 1, "PROMPT", "Child", "sebas", RULES_HASH, "[\"missing-parent@1\"]"))
                .expectStatus().isEqualTo(422).expectBody().jsonPath("$.code").isEqualTo("UNKNOWN_KNOWLEDGE_ASSET").jsonPath("$.details[0]").isEqualTo("missing-parent@1");
        post(admin, "/api/cryptobot/knowledge-assets", asset("bad-kind@1", 1, "VIBES", "Bad", "sebas", RULES_HASH, "[]")).expectStatus().isBadRequest();

        // V3/V4 refusals on the event: unknown asset, unknown provider, provider without wallet → 422, nothing written.
        post(admin, "/api/cryptobot/value-events", event("KAN-819", "[\"production-acceptance-model@1\",\"nope@1\"]", "[]", DEV_IMPLEMENTS))
                .expectStatus().isEqualTo(422).expectBody().jsonPath("$.code").isEqualTo("UNKNOWN_KNOWLEDGE_ASSET").jsonPath("$.details[0]").isEqualTo("nope@1");
        post(admin, "/api/cryptobot/value-events", event("KAN-819", "[]", compute("ghost-node"), DEV_IMPLEMENTS))
                .expectStatus().isEqualTo(422).expectBody().jsonPath("$.code").isEqualTo("UNKNOWN_COMPUTE_PROVIDER");
        post(admin, "/api/cryptobot/value-events", event("KAN-819", "[]", compute("sebas"), DEV_IMPLEMENTS))
                .expectStatus().isEqualTo(422).expectBody().jsonPath("$.code").isEqualTo("PROVIDER_WALLET_REQUIRED");
        post(admin, "/api/cryptobot/value-events", event("KAN-819", "[\"strategy-knowledge@3\",\"strategy-knowledge@3\"]", "[]", DEV_IMPLEMENTS))
                .expectStatus().isBadRequest().expectBody().jsonPath("$.code").isEqualTo("DUPLICATE_KNOWLEDGE_ASSET");
        assertThat(InMemoryPovRepositories.EVENTS).isEmpty();
        assertThat(InMemoryControlPlaneRepositories.RECEIPTS).isEmpty();

        // The event: 4 explicit contributions + sebas as KNOWLEDGE_PROVIDER (creator of both assets) → 5 × 20 units.
        JsonNode e1 = read(post(admin, "/api/cryptobot/value-events?anchor=true",
                event("KAN-819", "[\"production-acceptance-model@1\",\"strategy-knowledge@3\"]", compute("compute-node-8"), FULL_TEAM))
                .expectStatus().isCreated());
        assertThat(e1.path("status").asText()).isEqualTo("ANCHORED");
        List<String> roles = new ArrayList<>();
        e1.path("contributions").forEach(c -> roles.add(c.path("identityId").asText() + ":" + c.path("role").asText() + ":" + c.path("units").asInt()));
        assertThat(roles).containsExactly("sebas:SPECIFIER:20", "dev-agent-17:IMPLEMENTER:20", "review-agent-3:REVIEWER:20",
                "cryptobot-001:OPERATOR:20", "sebas:KNOWLEDGE_PROVIDER:20");
        assertThat(e1.path("contributions").get(4).path("derivedFrom").toString())
                .isEqualTo("[\"production-acceptance-model@1\",\"strategy-knowledge@3\"]");
        assertThat(e1.path("contributions").get(0).path("derivedFrom").isNull()).isTrue();
        JsonNode k0 = e1.path("knowledgeAssets").get(0);
        assertThat(k0.path("id").asText()).isEqualTo("production-acceptance-model@1");
        assertThat(k0.path("version").asInt()).isEqualTo(1);
        assertThat(k0.path("kind").asText()).isEqualTo("RULESET");
        assertThat(k0.path("creatorId").asText()).isEqualTo("sebas");
        assertThat(k0.path("contentHash").asText()).isEqualTo(RULES_HASH);
        JsonNode cr = e1.path("computeReceipts").get(0);
        assertThat(cr.path("id").asText()).matches("[0-9a-f-]{36}");
        assertThat(cr.path("providerId").asText()).isEqualTo("compute-node-8");
        assertThat(cr.path("providerDisplayName").asText()).isEqualTo("Compute Node 8");
        assertThat(cr.path("providerWallet").asText()).isEqualTo(W_COMPUTE);
        assertThat(cr.path("node").asText()).isEqualTo("gpu-node-8");
        assertThat(cr.path("model").asText()).isEqualTo("claude-opus");
        assertThat(cr.path("inputTokens").asLong()).isEqualTo(182000L);
        assertThat(cr.path("outputTokens").asLong()).isEqualTo(24000L);
        assertThat(cr.path("gpuSeconds").asDouble()).isEqualTo(12.5);
        assertThat(cr.path("estimatedCostMicroUsd").asLong()).isEqualTo(4_730_000L);

        // The receipt commits to the expanded event: schema v2, and the proof recomputes the hash from the stored canonical.
        JsonNode receipt = read(web.get().uri("/api/cryptobot/receipts/" + e1.path("receiptHash").asText()).header(HttpHeaders.AUTHORIZATION, admin)
                .exchange().expectStatus().isOk());
        assertThat(receipt.path("receipt").path("body").path("output").path("schema").asText()).isEqualTo("pov/value-event/v2");
        assertThat(receipt.path("receipt").path("body").path("output").path("hash").asText()).isEqualTo(e1.path("valueEventHash").asText());
        String canonical = InMemoryPovRepositories.EVENTS.values().iterator().next().canonical();
        assertThat(canonical).contains("\"contentHash\":\"" + RULES_HASH + "\"").contains("\"providerWallet\":\"" + W_COMPUTE + "\"")
                .contains("\"gpuMillis\":12500").contains("\"derivedFrom\":[\"production-acceptance-model@1\",\"strategy-knowledge@3\"]");
        assertThat(Digests.sha256(canonical)).isEqualTo(e1.path("valueEventHash").asText());
        JsonNode proof = read(web.get().uri("/api/cryptobot/value-events/" + e1.path("id").asText() + "/proof").header(HttpHeaders.AUTHORIZATION, admin)
                .exchange().expectStatus().isOk());
        assertThat(proof.path("verified").asBoolean()).isTrue();
        assertThat(proof.path("valueEventHashValid").asBoolean()).isTrue();

        // Same content again: the same event (auto contribution and compute receipt do not break idempotency).
        post(admin, "/api/cryptobot/value-events", event("KAN-819", "[\"production-acceptance-model@1\",\"strategy-knowledge@3\"]",
                compute("compute-node-8"), FULL_TEAM)).expectStatus().isOk().expectBody().jsonPath("$.id").isEqualTo(e1.path("id").asText());

        // A second, V1-shaped event in another project: sebas SPECIFIER, dev-agent-17 IMPLEMENTER → 50/50.
        JsonNode e2 = read(post(admin, "/api/cryptobot/value-events", event("KAN-900", "[]", "[]", DEV_IMPLEMENTS).replace("\"cryptobot\"", "\"portfolio\""))
                .expectStatus().isCreated());
        assertThat(e2.path("knowledgeAssets")).isEmpty();
        assertThat(e2.path("computeReceipts")).isEmpty();
        String e2Hash = read(web.get().uri("/api/cryptobot/receipts/" + e2.path("receiptHash").asText()).header(HttpHeaders.AUTHORIZATION, admin)
                .exchange().expectStatus().isOk()).path("receipt").path("body").path("output").path("schema").asText();
        assertThat(e2Hash).isEqualTo("pov/value-event/v1");

        // usedIn.
        web.get().uri("/api/cryptobot/knowledge-assets/strategy-knowledge@3").header(HttpHeaders.AUTHORIZATION, admin).exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.usedIn.length()").isEqualTo(1).jsonPath("$.usedIn[0]").isEqualTo(e1.path("id").asText())
                .jsonPath("$.creatorDisplayName").isEqualTo("Sebastián");
        JsonNode assets = read(web.get().uri("/api/cryptobot/knowledge-assets").header(HttpHeaders.AUTHORIZATION, admin).exchange().expectStatus().isOk());
        assertThat(assets).hasSize(2);
        web.get().uri("/api/cryptobot/knowledge-assets/nope@1").header(HttpHeaders.AUTHORIZATION, admin).exchange().expectStatus().isNotFound();

        // V2: two identities, two different histories; reputation is counts, nothing else.
        JsonNode dev = read(web.get().uri("/api/cryptobot/identities/dev-agent-17").header(HttpHeaders.AUTHORIZATION, admin).exchange().expectStatus().isOk());
        assertThat(dev.path("wallet").asText()).isEqualTo(W_DEV);
        assertThat(dev.path("ownerId").asText()).isEqualTo("sebas");
        assertThat(dev.path("reputation").path("acceptedOutcomes").asInt()).isEqualTo(2);
        assertThat(dev.path("reputation").path("totalUnits").asInt()).isEqualTo(70);
        assertThat(dev.path("history")).hasSize(2);
        assertThat(dev.path("history").get(0).path("valueEventId").asText()).isEqualTo(e2.path("id").asText());
        assertThat(dev.path("history").get(0).path("units").asInt()).isEqualTo(50);
        assertThat(dev.path("history").get(0).path("anchorStatus").asText()).isEqualTo("RECORDED");
        assertThat(dev.path("history").get(1).path("anchorStatus").asText()).isEqualTo("ANCHORED");
        assertThat(dev.path("history").get(1).path("role").asText()).isEqualTo("IMPLEMENTER");
        JsonNode review = read(web.get().uri("/api/cryptobot/identities/review-agent-3").header(HttpHeaders.AUTHORIZATION, admin).exchange().expectStatus().isOk());
        assertThat(review.path("reputation").path("acceptedOutcomes").asInt()).isEqualTo(1);
        assertThat(review.path("reputation").path("totalUnits").asInt()).isEqualTo(20);
        assertThat(review.path("history")).hasSize(1);
        JsonNode sebas = read(web.get().uri("/api/cryptobot/identities/sebas").header(HttpHeaders.AUTHORIZATION, admin).exchange().expectStatus().isOk());
        // Two roles in e1 (SPECIFIER + KNOWLEDGE_PROVIDER) are two history rows but one accepted outcome.
        assertThat(sebas.path("reputation").path("acceptedOutcomes").asInt()).isEqualTo(2);
        assertThat(sebas.path("reputation").path("totalUnits").asInt()).isEqualTo(90);
        assertThat(sebas.path("history")).hasSize(3);
        assertThat(sebas.path("reputation").path("firstAcceptedAt").asText()).isEqualTo("2026-09-30T10:00:00Z");
        assertThat(sebas.path("reputation").path("lastAcceptedAt").asText()).isEqualTo("2026-09-30T11:00:00Z");
        JsonNode list = read(web.get().uri("/api/cryptobot/identities").header(HttpHeaders.AUTHORIZATION, admin).exchange().expectStatus().isOk());
        assertThat(list).hasSize(5);
        list.forEach(i -> assertThat(i.has("history")).isFalse());
        JsonNode compute = null;
        for (JsonNode i : list) {
            if (i.path("id").asText().equals("compute-node-8")) {
                compute = i;
            }
        }
        // Compute cost is not value: the provider has no units unless it is a contributor.
        assertThat(compute.path("reputation").path("acceptedOutcomes").asInt()).isZero();
        assertThat(compute.path("reputation").path("firstAcceptedAt").isNull()).isTrue();

        // V6: the ledger, three ways; rows always sum to the 200 units of the two events.
        JsonNode byIdentity = ledger(admin, "identity");
        assertThat(byIdentity.path("totalUnits").asInt()).isEqualTo(200);
        assertThat(sum(byIdentity)).isEqualTo(200);
        assertThat(byIdentity.path("rows").get(0).path("key").asText()).isEqualTo("sebas");
        assertThat(byIdentity.path("rows").get(0).path("totalUnits").asInt()).isEqualTo(90);
        assertThat(byIdentity.path("rows").get(0).path("acceptedOutcomes").asInt()).isEqualTo(2);
        assertThat(byIdentity.path("rows").get(0).path("kind").asText()).isEqualTo("HUMAN");
        JsonNode byAsset = ledger(admin, "asset");
        assertThat(sum(byAsset)).isEqualTo(200);
        assertThat(rows(byAsset)).containsExactly("production-acceptance-model@1=10/1", "strategy-knowledge@3=10/1", "unattributed=180/2");
        assertThat(byAsset.path("rows").get(0).path("kind").asText()).isEqualTo("RULESET");
        JsonNode byProject = ledger(admin, "project");
        assertThat(sum(byProject)).isEqualTo(200);
        assertThat(rows(byProject)).containsExactly("cryptobot=100/1", "portfolio=100/1");
        web.get().uri("/api/cryptobot/units/ledger?groupBy=vibes").header(HttpHeaders.AUTHORIZATION, admin).exchange().expectStatus().isBadRequest()
                .expectBody().jsonPath("$.code").isEqualTo("INVALID_GROUP_BY");
        assertThat(read(web.get().uri("/api/cryptobot/units/ledger").header(HttpHeaders.AUTHORIZATION, admin).exchange().expectStatus().isOk())
                .path("groupBy").asText()).isEqualTo("identity");

        // Another tenant sees none of it.
        String other = ProofOfValueApiTest.bearer(UUID.randomUUID(), List.of("RUNTIME_OPERATOR"));
        web.get().uri("/api/cryptobot/knowledge-assets/strategy-knowledge@3").header(HttpHeaders.AUTHORIZATION, other).exchange().expectStatus().isNotFound();
        assertThat(ledger(other, "identity").path("totalUnits").asInt()).isZero();

        assertThat(meters.find("pov.knowledge.assets").counter().count()).isGreaterThanOrEqualTo(2.0);
        assertThat(meters.find("pov.compute.receipts").counter().count()).isGreaterThanOrEqualTo(1.0);
    }

    static final String DEV_IMPLEMENTS = "[{\"identityId\":\"sebas\",\"role\":\"SPECIFIER\"},{\"identityId\":\"dev-agent-17\",\"role\":\"IMPLEMENTER\"}]";
    static final String FULL_TEAM = "[{\"identityId\":\"sebas\",\"role\":\"SPECIFIER\"},{\"identityId\":\"dev-agent-17\",\"role\":\"IMPLEMENTER\"},"
            + "{\"identityId\":\"review-agent-3\",\"role\":\"REVIEWER\"},{\"identityId\":\"cryptobot-001\",\"role\":\"OPERATOR\"}]";

    private void seed(String token) {
        for (String body : List.of(
                "{\"id\":\"sebas\",\"kind\":\"HUMAN\",\"displayName\":\"Sebastián\"}",
                "{\"id\":\"dev-agent-17\",\"kind\":\"AGENT\",\"displayName\":\"Dev Agent 17\",\"wallet\":\"" + W_DEV + "\",\"ownerId\":\"sebas\"}",
                "{\"id\":\"cryptobot-001\",\"kind\":\"AGENT\",\"displayName\":\"CryptoBot 001\",\"wallet\":\"" + W_BOT + "\",\"ownerId\":\"sebas\",\"operatorId\":\"sebas\"}",
                "{\"id\":\"review-agent-3\",\"kind\":\"AGENT\",\"displayName\":\"Review Agent 3\",\"wallet\":\"" + W_REVIEW + "\"}",
                "{\"id\":\"compute-node-8\",\"kind\":\"AGENT\",\"displayName\":\"Compute Node 8\",\"wallet\":\"" + W_COMPUTE + "\",\"ownerId\":\"sebas\"}")) {
            post(token, "/api/cryptobot/identities", body).expectStatus().isCreated();
        }
    }

    static String asset(String id, int version, String kind, String title, String creator, String hash, String parents) {
        return "{\"id\":\"" + id + "\",\"version\":" + version + ",\"kind\":\"" + kind + "\",\"title\":\"" + title + "\",\"creatorId\":\"" + creator
                + "\",\"contentHash\":\"" + hash + "\",\"parentIds\":" + parents + "}";
    }

    static String compute(String provider) {
        return "[{\"providerId\":\"" + provider + "\",\"node\":\"gpu-node-8\",\"model\":\"claude-opus\",\"inputTokens\":182000,\"outputTokens\":24000,"
                + "\"gpuSeconds\":12.5,\"estimatedCostMicroUsd\":4730000}]";
    }

    /** taskId KAN-819 is accepted at 10:00, any other at 11:00. */
    static String event(String taskId, String assets, String compute, String contributions) {
        String at = "KAN-819".equals(taskId) ? "2026-09-30T10:00:00Z" : "2026-09-30T11:00:00Z";
        return "{\"projectId\":\"cryptobot\",\"taskId\":\"" + taskId + "\",\"title\":\"Improve CryptoBot opportunity detection\","
                + "\"artifact\":{\"commitSha\":\"" + ProofOfValueApiTest.COMMIT + "\"},"
                + "\"acceptance\":{\"source\":\"release-truth\",\"environment\":\"uat-k8s\",\"stages\":{\"MERGED\":true,\"BUILT\":true,\"DEPLOYED\":true,"
                + "\"RUNNING\":true,\"ACCEPTED\":true},\"acceptedAt\":\"" + at + "\"},"
                + "\"contributions\":" + contributions + ",\"knowledgeAssets\":" + assets + ",\"computeReceipts\":" + compute + "}";
    }

    private JsonNode ledger(String token, String groupBy) throws Exception {
        return read(web.get().uri("/api/cryptobot/units/ledger?groupBy=" + groupBy).header(HttpHeaders.AUTHORIZATION, token).exchange().expectStatus().isOk());
    }

    private static int sum(JsonNode ledger) {
        int s = 0;
        for (JsonNode r : ledger.path("rows")) {
            s += r.path("totalUnits").asInt();
        }
        return s;
    }

    private static List<String> rows(JsonNode ledger) {
        List<String> out = new ArrayList<>();
        ledger.path("rows").forEach(r -> out.add(r.path("key").asText() + "=" + r.path("totalUnits").asInt() + "/" + r.path("acceptedOutcomes").asInt()));
        return out;
    }

    private WebTestClient.ResponseSpec post(String token, String uri, String body) {
        return web.post().uri(uri).header(HttpHeaders.AUTHORIZATION, token).contentType(MediaType.APPLICATION_JSON).bodyValue(body).exchange();
    }

    private static JsonNode read(WebTestClient.ResponseSpec spec) throws Exception {
        return JSON.readTree(spec.expectBody().returnResult().getResponseBody());
    }
}
