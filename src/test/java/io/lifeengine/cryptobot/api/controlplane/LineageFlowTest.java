package io.lifeengine.cryptobot.api.controlplane;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import io.lifeengine.cryptobot.CryptobotServiceApplication;
import io.lifeengine.cryptobot.testsupport.InMemoryControlPlaneRepositories;
import io.lifeengine.cryptobot.testsupport.StubRepositoriesConfiguration;
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
 * KAN-393 over HTTP, with the same fake Solana RPC and fake Runtime as {@link ControlPlaneFlowTest}:
 * a proposal created <em>without</em> naming the advisor run reuses the fresh analysis of the same
 * asset ({@code REUSES}), and the lineage API returns the DAG the UI draws — nodes with hash,
 * producer, compute, level and anchor; typed edges; summary — owner-scoped and depth-bounded.
 */
@SpringBootTest(
        classes = {CryptobotServiceApplication.class, StubRepositoriesConfiguration.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient(timeout = "30s")
@ActiveProfiles("test")
class LineageFlowTest {

    private static MockWebServer rpc;
    private static MockWebServer runtime;
    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired private WebTestClient web;

    @BeforeAll
    static void startMocks() throws Exception {
        rpc = new MockWebServer();
        rpc.setDispatcher(new ControlPlaneFlowTest.SolanaDispatcher());
        rpc.start();
        runtime = new MockWebServer();
        runtime.setDispatcher(new ControlPlaneFlowTest.RuntimeDispatcher());
        runtime.start();
    }

    @AfterAll
    static void stopMocks() throws Exception {
        rpc.shutdown();
        runtime.shutdown();
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("cryptobot.solana.rpc.devnet-url", () -> "http://localhost:" + rpc.getPort());
        r.add("cryptobot.solana.rpc.mainnet-url", () -> "http://localhost:" + rpc.getPort());
        r.add("cryptobot.runtime.base-url", () -> "http://localhost:" + runtime.getPort());
    }

    @BeforeEach
    void reset() {
        InMemoryControlPlaneRepositories.reset();
    }

    private JsonNode get(String uri, String token) throws Exception {
        return JSON.readTree(web.get().uri(uri).header(HttpHeaders.AUTHORIZATION, token)
                .exchange().expectStatus().isOk().expectBody().returnResult().getResponseBody());
    }

    private static List<String> kinds(JsonNode graph) {
        List<String> out = new ArrayList<>();
        graph.path("nodes").forEach(n -> out.add(n.path("kind").asText()));
        return out;
    }

    private static JsonNode node(JsonNode graph, String kind) {
        for (JsonNode n : graph.path("nodes")) {
            if (kind.equals(n.path("kind").asText())) {
                return n;
            }
        }
        throw new AssertionError("no node of kind " + kind);
    }

    @Test
    void proposalWithoutRunReusesTheFreshAnalysisAndTheLineageApiReturnsTheDag() throws Exception {
        UUID user = UUID.randomUUID();
        String token = bearer(user);

        // 1. Wallet + snapshot (WALLET_SNAPSHOT, RISK_DECISION), then the advisor (HUMAN_IDEA, MARKET_ANALYSIS about SOL).
        JsonNode created = JSON.readTree(web.post().uri("/api/cryptobot/wallets").header(HttpHeaders.AUTHORIZATION, token)
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{\"address\":\"" + ControlPlaneFlowTest.ADDRESS + "\",\"cluster\":\"devnet\"}")
                .exchange().expectStatus().isCreated().expectBody().returnResult().getResponseBody());
        String walletId = created.path("wallet").path("id").asText();
        JsonNode asked = JSON.readTree(web.post().uri("/api/cryptobot/wallets/" + walletId + "/ask").header(HttpHeaders.AUTHORIZATION, token)
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{\"question\":\"What is my biggest risk?\"}")
                .exchange().expectStatus().isOk().expectBody().returnResult().getResponseBody());
        String analysisHash = asked.path("receiptHash").asText();
        assertThat(asked.path("answer").path("suggestedActions").get(0).path("asset").asText()).isEqualTo("SOL");

        // 2. A proposal on SOL without naming the run: the strategy REUSES the analysis (same asset, < 1 h).
        JsonNode proposed = JSON.readTree(web.post().uri("/api/cryptobot/wallets/" + walletId + "/proposals").header(HttpHeaders.AUTHORIZATION, token)
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{\"kind\":\"REBALANCE\",\"targetWeights\":{\"SOL\":50}}")
                .exchange().expectStatus().isCreated().expectBody().returnResult().getResponseBody());
        String proposalId = proposed.path("proposal").path("id").asText();
        assertThat(proposed.path("proposal").path("runtimeRunId").isNull()).isTrue();
        JsonNode createdEvent = get("/api/cryptobot/proposals/" + proposalId + "/audit", token).get(0);
        assertThat(createdEvent.path("eventType").asText()).isEqualTo("PROPOSAL_CREATED");
        assertThat(createdEvent.path("payload").path("analysisReceipt").asText()).isEqualTo(analysisHash);
        assertThat(createdEvent.path("payload").path("analysisRole").asText()).isEqualTo("REUSES");

        JsonNode proposalReceipts = get("/api/cryptobot/proposals/" + proposalId + "/receipts", token);
        String strategyHash = proposalReceipts.get(0).path("receiptHash").asText();
        assertThat(proposalReceipts.get(0).path("body").path("kind").asText()).isEqualTo("STRATEGY");
        JsonNode strategy = get("/api/cryptobot/receipts/" + strategyHash, token);
        List<String> roles = new ArrayList<>();
        strategy.path("parents").forEach(e -> roles.add(e.path("parentHash").asText() + ":" + e.path("role").asText()));
        assertThat(roles).contains(analysisHash + ":REUSES");
        assertThat(roles).anyMatch(r -> r.endsWith(":DERIVES_FROM")); // the snapshot

        // reusedBy from the analysis' side: exactly the strategy.
        JsonNode reusedBy = get("/api/cryptobot/receipts/" + analysisHash + "/reused-by", token);
        assertThat(reusedBy).hasSize(1);
        assertThat(reusedBy.get(0).path("role").asText()).isEqualTo("REUSES");
        assertThat(reusedBy.get(0).path("node").path("kind").asText()).isEqualTo("STRATEGY");
        assertThat(reusedBy.get(0).path("node").path("receiptHash").asText()).isEqualTo(strategyHash);

        // 3. The DAG of the proposal: its 3 receipts at depth 0, the 4 they came from above; typed edges; honest summary.
        JsonNode graph = get("/api/cryptobot/proposals/" + proposalId + "/lineage", token);
        assertThat(graph.path("direction").asText()).isEqualTo("ANCESTORS");
        assertThat(graph.path("maxDepth").asInt()).isEqualTo(16);
        assertThat(graph.path("truncated").asBoolean()).isFalse();
        assertThat(graph.path("roots")).hasSize(3);
        assertThat(kinds(graph)).containsExactlyInAnyOrder("STRATEGY", "RISK_DECISION", "SIMULATION", "WALLET_SNAPSHOT", "RISK_DECISION", "HUMAN_IDEA", "MARKET_ANALYSIS");
        assertThat(graph.path("edges")).hasSize(9);
        List<String> edgeRoles = new ArrayList<>();
        graph.path("edges").forEach(e -> edgeRoles.add(e.path("role").asText()));
        assertThat(edgeRoles).containsOnlyOnce("REUSES").containsOnlyOnce("VALIDATES");
        JsonNode snapshotNode = node(graph, "WALLET_SNAPSHOT");
        assertThat(graph.path("lineageRoots")).hasSize(1);
        assertThat(graph.path("lineageRoots").get(0).asText()).isEqualTo(snapshotNode.path("receiptHash").asText());
        assertThat(snapshotNode.path("parentCount").asInt()).isZero();
        assertThat(snapshotNode.path("childCount").asInt()).isEqualTo(4); // risk, idea, analysis, strategy
        JsonNode llm = node(graph, "MARKET_ANALYSIS");
        assertThat(llm.path("model").path("ref").asText()).isEqualTo("qwen3:14b");
        assertThat(llm.path("compute").path("inputTokens").asLong()).isEqualTo(3512);
        assertThat(llm.path("level").asText()).isEqualTo("L0_SIGNED");
        assertThat(llm.path("depth").asInt()).isEqualTo(1);
        assertThat(llm.path("anchor").isNull()).isTrue();
        assertThat(llm.path("cost").isNull()).isTrue();
        assertThat(llm.path("runId").asText()).isEqualTo(asked.path("runtimeRunId").asText());
        JsonNode strategyNode = node(graph, "STRATEGY");
        assertThat(strategyNode.path("depth").asInt()).isZero();
        assertThat(strategyNode.path("engine").path("id").asText()).isEqualTo("rebalance-planner");
        assertThat(strategyNode.path("refs").path("proposalId").asText()).isEqualTo(proposalId);
        JsonNode summary = graph.path("summary");
        assertThat(summary.path("nodes").asInt()).isEqualTo(7);
        assertThat(summary.path("edges").asInt()).isEqualTo(9);
        assertThat(summary.path("inputTokens").asLong()).isEqualTo(3512);
        assertThat(summary.path("outputTokens").asLong()).isEqualTo(240);
        assertThat(summary.path("computeUnits").asLong()).isEqualTo(3512 + 3 * 240 + 5); // the LLM step + 5 × 1 unit; HUMAN_IDEA measures nothing
        assertThat(summary.path("costUsd").isNull()).as("no price table ⇒ no cost claimed").isTrue();
        assertThat(summary.path("anchored").asInt()).isZero();
        assertThat(summary.path("reused").asInt()).isEqualTo(1);
        assertThat(summary.path("byLevel").path("L1_REPRODUCIBLE").asInt()).isEqualTo(3);
        assertThat(summary.path("byLevel").path("L0_SIGNED").asInt()).isEqualTo(4);
        // Nothing private in the graph: no question, no answer, no key, no transaction.
        assertThat(graph.toString()).doesNotContain("biggest risk").doesNotContain("drawdown").doesNotContain("unsignedTransaction").doesNotContain("signatureBase64");

        // 4. From a receipt: descendants of the snapshot are everything; ancestors of the strategy at depth 1 are cut short.
        String snapshotHash = snapshotNode.path("receiptHash").asText();
        JsonNode down = get("/api/cryptobot/receipts/" + snapshotHash + "/descendants", token);
        assertThat(down.path("nodes")).hasSize(7);
        assertThat(down.path("roots").get(0).asText()).isEqualTo(snapshotHash);
        JsonNode shallow = get("/api/cryptobot/receipts/" + strategyHash + "/ancestors?depth=1", token);
        assertThat(kinds(shallow)).containsExactlyInAnyOrder("STRATEGY", "WALLET_SNAPSHOT", "MARKET_ANALYSIS");
        assertThat(shallow.path("truncated").asBoolean()).isTrue();
        JsonNode both = get("/api/cryptobot/receipts/" + strategyHash + "/lineage?direction=both&depth=3", token);
        assertThat(both.path("nodes")).hasSize(7);
        assertThat(both.path("direction").asText()).isEqualTo("BOTH");
        JsonNode parents = get("/api/cryptobot/receipts/" + strategyHash + "/parents", token);
        assertThat(parents).hasSize(2);
        JsonNode children = get("/api/cryptobot/receipts/" + strategyHash + "/children", token);
        assertThat(children).hasSize(2);
        List<String> childRoles = new ArrayList<>();
        children.forEach(c -> childRoles.add(c.path("role").asText()));
        assertThat(childRoles).containsExactlyInAnyOrder("VALIDATES", "DERIVES_FROM");

        // 5. A proposal that names the run derives from the analysis instead of reusing it; reusedBy is unchanged.
        JsonNode named = JSON.readTree(web.post().uri("/api/cryptobot/wallets/" + walletId + "/proposals").header(HttpHeaders.AUTHORIZATION, token)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"kind\":\"REBALANCE\",\"targetWeights\":{\"SOL\":55},\"runtimeRunId\":\"" + asked.path("runtimeRunId").asText() + "\"}")
                .exchange().expectStatus().isCreated().expectBody().returnResult().getResponseBody());
        assertThat(get("/api/cryptobot/proposals/" + named.path("proposal").path("id").asText() + "/audit", token).get(0).path("payload").path("analysisRole").asText())
                .isEqualTo("DERIVES_FROM");
        assertThat(get("/api/cryptobot/receipts/" + analysisHash + "/reused-by", token)).hasSize(1);
        assertThat(get("/api/cryptobot/receipts/" + analysisHash + "/children", token)).hasSize(2);

        // 6. Scoping and validation: another user sees nothing; a bad direction is a 400; no token is a 401.
        web.get().uri("/api/cryptobot/proposals/" + proposalId + "/lineage").header(HttpHeaders.AUTHORIZATION, bearer(UUID.randomUUID()))
                .exchange().expectStatus().isNotFound();
        web.get().uri("/api/cryptobot/receipts/" + strategyHash + "/lineage").header(HttpHeaders.AUTHORIZATION, bearer(UUID.randomUUID()))
                .exchange().expectStatus().isNotFound();
        web.get().uri("/api/cryptobot/receipts/" + analysisHash + "/reused-by").header(HttpHeaders.AUTHORIZATION, bearer(UUID.randomUUID()))
                .exchange().expectStatus().isNotFound();
        web.get().uri("/api/cryptobot/receipts/" + strategyHash + "/lineage?direction=sideways").header(HttpHeaders.AUTHORIZATION, token)
                .exchange().expectStatus().isBadRequest().expectBody().jsonPath("$.code").isEqualTo("INVALID_DIRECTION");
        web.get().uri("/api/cryptobot/receipts/not-a-hash/lineage").header(HttpHeaders.AUTHORIZATION, token)
                .exchange().expectStatus().isBadRequest().expectBody().jsonPath("$.code").isEqualTo("INVALID_RECEIPT_HASH");
        web.get().uri("/api/cryptobot/proposals/" + proposalId + "/lineage").exchange().expectStatus().isUnauthorized();

        // 7. The reuse and the depth are measured.
        String scrape = web.get().uri("/actuator/prometheus").exchange().expectStatus().isOk().expectBody(String.class).returnResult().getResponseBody();
        assertThat(scrape).contains("artifact_reuse_total{").contains("provenance_depth");
    }

    @Test
    void proposalWithoutAnyAnalysisHasNoReuseAndAnEmptyLineageIsStillAGraph() throws Exception {
        String token = bearer(UUID.randomUUID());
        JsonNode created = JSON.readTree(web.post().uri("/api/cryptobot/wallets").header(HttpHeaders.AUTHORIZATION, token)
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{\"address\":\"" + ControlPlaneFlowTest.ADDRESS + "\",\"cluster\":\"devnet\"}")
                .exchange().expectStatus().isCreated().expectBody().returnResult().getResponseBody());
        String walletId = created.path("wallet").path("id").asText();
        JsonNode proposed = JSON.readTree(web.post().uri("/api/cryptobot/wallets/" + walletId + "/proposals").header(HttpHeaders.AUTHORIZATION, token)
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{\"targetWeights\":{\"SOL\":50}}")
                .exchange().expectStatus().isCreated().expectBody().returnResult().getResponseBody());
        String proposalId = proposed.path("proposal").path("id").asText();
        assertThat(get("/api/cryptobot/proposals/" + proposalId + "/audit", token).get(0).path("payload").has("analysisReceipt")).isFalse();
        JsonNode graph = get("/api/cryptobot/proposals/" + proposalId + "/lineage", token);
        assertThat(kinds(graph)).containsExactlyInAnyOrder("STRATEGY", "RISK_DECISION", "SIMULATION", "WALLET_SNAPSHOT");
        assertThat(graph.path("summary").path("reused").asInt()).isZero();
        assertThat(graph.path("edges")).hasSize(3);

        // A proposal id that left no receipts (or does not exist for this owner): 404 for the owner check first.
        web.get().uri("/api/cryptobot/proposals/" + UUID.randomUUID() + "/lineage").header(HttpHeaders.AUTHORIZATION, token)
                .exchange().expectStatus().isNotFound();
    }

    private static String bearer(UUID userId) {
        SecretKey key = Keys.hmacShaKeyFor("test-jwt-secret-at-least-32-bytes-long!!".getBytes(StandardCharsets.UTF_8));
        return "Bearer " + Jwts.builder()
                .subject(userId.toString())
                .claim("email", "operator@test.local")
                .claim("authorities", List.of("RUNTIME_OPERATOR"))
                .issuedAt(java.util.Date.from(Instant.now()))
                .expiration(java.util.Date.from(Instant.now().plusSeconds(300)))
                .signWith(key)
                .compact();
    }
}
