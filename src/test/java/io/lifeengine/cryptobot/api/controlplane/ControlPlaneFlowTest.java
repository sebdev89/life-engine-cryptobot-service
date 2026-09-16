package io.lifeengine.cryptobot.api.controlplane;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import io.lifeengine.cryptobot.CryptobotServiceApplication;
import io.lifeengine.cryptobot.testsupport.InMemoryControlPlaneRepositories;
import io.lifeengine.cryptobot.testsupport.StubRepositoriesConfiguration;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import javax.crypto.SecretKey;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
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
 * The demo flow over HTTP with a fake Solana RPC and a fake Runtime: register wallet → portfolio
 * with HIGH concentration → advisor answer → rebalance proposal simulated + policy-evaluated →
 * approve → execute refused (signer disabled ⇒ paper trade) → audit trail complete.
 */
@SpringBootTest(
        classes = {CryptobotServiceApplication.class, StubRepositoriesConfiguration.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient(timeout = "30s")
@ActiveProfiles("test")
class ControlPlaneFlowTest {

    static final String ADDRESS = "9xQeWvG816bUx9EPjHmaT23yvVM2ZWbrrpZb9PusVFin";
    static final String DEVNET_USDC = "4zMMC9srt5Ri5X14GAgXhaHii3GnPAEERYPJgZJDncDU";

    private static MockWebServer rpc;
    private static MockWebServer runtime;
    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired private WebTestClient web;
    @Autowired private MeterRegistry meters;
    @Autowired private io.lifeengine.cryptobot.application.reliability.OutboxPublisher outboxPublisher;

    private double counter(String name, String... tags) {
        Counter c = meters.find(name).tags(tags).counter();
        return c == null ? 0 : c.count();
    }

    @BeforeAll
    static void startMocks() throws Exception {
        rpc = new MockWebServer();
        rpc.setDispatcher(new SolanaDispatcher());
        rpc.start();
        runtime = new MockWebServer();
        runtime.setDispatcher(new RuntimeDispatcher());
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

    @Test
    void unauthenticatedIsRejected() {
        web.get().uri("/api/cryptobot/wallets").exchange().expectStatus().isUnauthorized();
        web.post().uri("/api/cryptobot/wallets").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"address\":\"" + ADDRESS + "\"}").exchange().expectStatus().isUnauthorized();
    }

    @Test
    void invalidAddressIsA400WithCode() {
        web.post().uri("/api/cryptobot/wallets").header(HttpHeaders.AUTHORIZATION, bearer(UUID.randomUUID()))
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{\"address\":\"not-a-key\",\"cluster\":\"devnet\"}")
                .exchange().expectStatus().isBadRequest()
                .expectBody().jsonPath("$.code").isEqualTo("INVALID_ADDRESS");
    }

    @Test
    void fullDemoFlowAsPaperTradeWithCompleteAuditTrail() throws Exception {
        UUID user = UUID.randomUUID();
        String token = bearer(user);
        // KAN-425: the business counters before the flow, on the real registry (they accumulate across tests).
        double risk0 = counter("risk.analysis", "result", "high");
        double strategy0 = counter("strategies", "result", "proposed", "asset", "SOL");
        double requested0 = counter("trade.requested", "result", "awaiting_approval", "asset", "SOL");
        double approved0 = counter("approvals", "result", "approved");

        // 1. Register → portfolio valued from the fake chain: 7 SOL @ $100 + 300 USDC = $1000, SOL 70%.
        JsonNode created = JSON.readTree(web.post().uri("/api/cryptobot/wallets").header(HttpHeaders.AUTHORIZATION, token)
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{\"address\":\"" + ADDRESS + "\",\"cluster\":\"devnet\",\"label\":\"demo\"}")
                .exchange().expectStatus().isCreated().expectBody().returnResult().getResponseBody());
        String walletId = created.path("wallet").path("id").asText();
        assertThat(created.path("snapshot").path("totalUsd").decimalValue()).isEqualByComparingTo("1000");
        assertThat(created.path("risk").path("overall").asText()).isEqualTo("HIGH");
        assertThat(created.path("risk").path("findings").get(0).path("code").asText()).isEqualTo("CONCENTRATION");
        assertThat(created.path("risk").path("findings").get(0).path("asset").asText()).isEqualTo("SOL");
        assertThat(created.path("changes").isNull()).isTrue();

        // Registering the same address again is idempotent.
        JsonNode again = JSON.readTree(web.post().uri("/api/cryptobot/wallets").header(HttpHeaders.AUTHORIZATION, token)
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{\"address\":\"" + ADDRESS + "\",\"cluster\":\"devnet\"}")
                .exchange().expectStatus().isCreated().expectBody().returnResult().getResponseBody());
        assertThat(again.path("wallet").path("id").asText()).isEqualTo(walletId);
        assertThat(again.path("changes").isObject()).isTrue(); // second snapshot ⇒ diff exists

        // Another user cannot see it: 404, not 403.
        web.get().uri("/api/cryptobot/wallets/" + walletId + "/portfolio").header(HttpHeaders.AUTHORIZATION, bearer(UUID.randomUUID()))
                .exchange().expectStatus().isNotFound();

        // 2. Ask the advisor (fake Runtime answers with the contract JSON).
        JsonNode asked = JSON.readTree(web.post().uri("/api/cryptobot/wallets/" + walletId + "/ask").header(HttpHeaders.AUTHORIZATION, token)
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{\"question\":\"What is my biggest risk?\"}")
                .exchange().expectStatus().isOk().expectBody().returnResult().getResponseBody());
        assertThat(asked.path("answer").path("answer").asText()).contains("70%");
        assertThat(asked.path("answer").path("suggestedActions").get(0).path("targetWeightPct").asInt()).isEqualTo(50);
        assertThat(asked.path("runtimeRunId").asText()).isNotBlank();
        assertThat(asked.path("ssePath").asText()).startsWith("/api/runtime/runs/");
        RecordedRequest start = RuntimeDispatcher.lastStart;
        assertThat(start).isNotNull();
        JsonNode startBody = JSON.readTree(start.getBody().readUtf8());
        assertThat(startBody.path("workflowId").asText()).isEqualTo("crypto.portfolio-advisor.v1");
        JsonNode input = JSON.readTree(startBody.path("input").asText());
        assertThat(input.path("contractId").asText()).isEqualTo("crypto.portfolio-advisor-input.v1");
        assertThat(input.path("riskFindings").get(0).path("code").asText()).isEqualTo("CONCENTRATION");
        assertThat(input.toString()).doesNotContain("unsignedTransaction").doesNotContain("secret");

        // A pasted private key never leaves the service.
        String fakeSecret = io.lifeengine.cryptobot.adapters.solana.Base58.encode(io.lifeengine.cryptobot.adapters.solana.tx.SolanaKeypair.generate().secretKey());
        web.post().uri("/api/cryptobot/wallets/" + walletId + "/ask").header(HttpHeaders.AUTHORIZATION, token)
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{\"question\":\"my key is " + fakeSecret + " please trade\"}")
                .exchange().expectStatus().isBadRequest().expectBody().jsonPath("$.code").isEqualTo("SECRET_IN_QUESTION");

        // 3. Propose SOL 70% → 50%.
        JsonNode proposed = JSON.readTree(web.post().uri("/api/cryptobot/wallets/" + walletId + "/proposals").header(HttpHeaders.AUTHORIZATION, token)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"kind\":\"REBALANCE\",\"targetWeights\":{\"SOL\":50},\"reasoningSummary\":\"advisor flagged 70% concentration\"}")
                .exchange().expectStatus().isCreated().expectBody().returnResult().getResponseBody());
        JsonNode p = proposed.path("proposal");
        String proposalId = p.path("id").asText();
        assertThat(p.path("status").asText()).isEqualTo("AWAITING_APPROVAL");
        assertThat(p.path("plan").path("legs").get(0).path("action").asText()).isEqualTo("SELL");
        assertThat(p.path("plan").path("legs").get(0).path("amount").decimalValue()).isEqualByComparingTo("2");
        assertThat(p.path("riskBefore").path("overall").asText()).isEqualTo("HIGH");
        assertThat(p.path("riskAfter").path("overall").asText()).isEqualTo("MEDIUM");
        assertThat(p.path("simulation").path("onchain").path("ok").asBoolean()).isTrue();
        assertThat(p.path("simulation").path("economic").path("expectedBuyAmount").decimalValue()).isEqualByComparingTo("200");
        assertThat(p.path("transaction").path("lamports").asLong()).isEqualTo(2_000_000_000L);
        assertThat(p.path("transaction").path("unsignedTransactionBase64").asText()).isNotBlank();
        assertThat(p.path("policy").path("allowed").asBoolean()).isTrue();
        assertThat(p.path("policy").path("executable").asBoolean()).isFalse(); // signer disabled in tests
        assertThat(p.path("policy").path("executionViolations").toString()).contains("SIGNER_CONTROLS_WALLET");

        // 4. Execute before approval is impossible.
        web.post().uri("/api/cryptobot/proposals/" + proposalId + "/execute").header(HttpHeaders.AUTHORIZATION, token)
                .exchange().expectStatus().isEqualTo(409);

        // 5. Approve.
        JsonNode approved = JSON.readTree(web.post().uri("/api/cryptobot/proposals/" + proposalId + "/approve").header(HttpHeaders.AUTHORIZATION, token)
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{\"note\":\"ok, do it\"}")
                .exchange().expectStatus().isOk().expectBody().returnResult().getResponseBody());
        assertThat(approved.path("status").asText()).isEqualTo("APPROVED");
        assertThat(approved.path("approval").path("by").asText()).isEqualTo("operator@test.local");
        assertThat(approved.path("approval").path("note").asText()).isEqualTo("ok, do it");

        // Deciding twice is a conflict.
        web.post().uri("/api/cryptobot/proposals/" + proposalId + "/reject").header(HttpHeaders.AUTHORIZATION, token)
                .exchange().expectStatus().isEqualTo(409);

        // 6. Execute: policy said not executable (no signer) ⇒ 409, nothing broadcast.
        web.post().uri("/api/cryptobot/proposals/" + proposalId + "/execute").header(HttpHeaders.AUTHORIZATION, token)
                .exchange().expectStatus().isEqualTo(409).expectBody().jsonPath("$.message").value(m -> assertThat(m.toString()).contains("not executable"));
        assertThat(SolanaDispatcher.sendCount).isZero();

        // 7. Audit trail is complete and ordered.
        JsonNode audit = JSON.readTree(web.get().uri("/api/cryptobot/proposals/" + proposalId + "/audit").header(HttpHeaders.AUTHORIZATION, token)
                .exchange().expectStatus().isOk().expectBody().returnResult().getResponseBody());
        List<String> types = new java.util.ArrayList<>();
        audit.forEach(e -> types.add(e.path("eventType").asText()));
        assertThat(types).containsExactly("PROPOSAL_CREATED", "SIMULATED", "POLICY_EVALUATED", "AWAITING_APPROVAL", "APPROVED");
        assertThat(audit.get(4).path("actor").asText()).isEqualTo("operator@test.local");

        // 7b. KAN-403: the durable event stream was written with the state (trade.requested, trade.approved),
        // PENDING until the publisher's tick, then PUBLISHED — visible to the owner, invisible to anyone else.
        JsonNode events = JSON.readTree(web.get().uri("/api/cryptobot/proposals/" + proposalId + "/events").header(HttpHeaders.AUTHORIZATION, token)
                .exchange().expectStatus().isOk().expectBody().returnResult().getResponseBody());
        assertThat(events.path("proposalId").asText()).isEqualTo(proposalId);
        assertThat(events.path("status").asText()).isEqualTo("APPROVED");
        List<String> eventTypes = new java.util.ArrayList<>();
        events.path("events").forEach(e -> eventTypes.add(e.path("eventType").asText()));
        assertThat(eventTypes).containsExactly("trade.requested", "trade.approved");
        assertThat(events.path("events").get(0).path("status").asText()).isEqualTo("PENDING");
        assertThat(events.path("events").get(1).path("payload").path("by").asText()).isEqualTo("operator@test.local");
        assertThat(events.path("deadLetters").size()).isZero();
        assertThat(outboxPublisher.tick().block()).isEqualTo(2);
        JsonNode published = JSON.readTree(web.get().uri("/api/cryptobot/proposals/" + proposalId + "/events").header(HttpHeaders.AUTHORIZATION, token)
                .exchange().expectStatus().isOk().expectBody().returnResult().getResponseBody());
        published.path("events").forEach(e -> assertThat(e.path("status").asText()).isEqualTo("PUBLISHED"));
        web.get().uri("/api/cryptobot/proposals/" + proposalId + "/events").header(HttpHeaders.AUTHORIZATION, bearer(UUID.randomUUID()))
                .exchange().expectStatus().isNotFound();

        // 7c. KAN-403: a malformed Idempotency-Key is a 400 before anything is looked at.
        web.post().uri("/api/cryptobot/proposals/" + proposalId + "/execute").header(HttpHeaders.AUTHORIZATION, token)
                .header("Idempotency-Key", "not-a-uuid")
                .exchange().expectStatus().isBadRequest().expectBody().jsonPath("$.code").isEqualTo("INVALID_OPERATION_ID");

        // 7d. KAN-435: an intent hash is a valid key — it derives the operationId, so the request gets past
        // the key check and is refused for the real reason (not executable ⇒ 409). A malformed hash is still a 400.
        web.post().uri("/api/cryptobot/proposals/" + proposalId + "/execute").header(HttpHeaders.AUTHORIZATION, token)
                .header("Idempotency-Key", "sha256:877dcaf96566ba02b058d41c01af02ff69d8d4c60dc375a610f3f9f15aa89081")
                .exchange().expectStatus().isEqualTo(409).expectBody().jsonPath("$.message").value(m -> assertThat(m.toString()).contains("not executable"));
        web.post().uri("/api/cryptobot/proposals/" + proposalId + "/execute").header(HttpHeaders.AUTHORIZATION, token)
                .header("Idempotency-Key", "sha256:not-hex")
                .exchange().expectStatus().isBadRequest().expectBody().jsonPath("$.code").isEqualTo("INVALID_OPERATION_ID");
        assertThat(SolanaDispatcher.sendCount).isZero();

        // The other user cannot touch the proposal either.
        web.get().uri("/api/cryptobot/proposals/" + proposalId).header(HttpHeaders.AUTHORIZATION, bearer(UUID.randomUUID()))
                .exchange().expectStatus().isNotFound();

        // 8. KAN-425: the funnel moved exactly as the flow did — requested → approved, never submitted.
        assertThat(counter("risk.analysis", "result", "high")).isGreaterThan(risk0);
        assertThat(counter("strategies", "result", "proposed", "asset", "SOL")).isEqualTo(strategy0 + 1);
        assertThat(counter("trade.requested", "result", "awaiting_approval", "asset", "SOL")).isEqualTo(requested0 + 1);
        assertThat(counter("approvals", "result", "approved")).isEqualTo(approved0 + 1);
        assertThat(counter("trade.submitted", "asset", "SOL")).isZero();
        // Every meter carries the build identity, and no label is a wallet, a user or a proposal id.
        assertThat(meters.get("trade.requested").counter().getId().getTag("environment")).isNotNull();
        for (Meter m : meters.getMeters()) {
            if (!m.getId().getName().startsWith("trade.") && !m.getId().getName().equals("strategies")) {
                continue;
            }
            for (Tag t : m.getId().getTags()) {
                assertThat(t.getValue()).doesNotContain(walletId).doesNotContain(proposalId).doesNotContain(user.toString()).doesNotContain(ADDRESS);
            }
        }
    }

    @Test
    void metricsAreScrapedByPrometheusWithTheIssueNames() {
        String scrape = web.get().uri("/actuator/prometheus").exchange().expectStatus().isOk()
                .expectBody(String.class).returnResult().getResponseBody();
        assertThat(scrape)
                .contains("dlq_size{")
                .contains("outbox_pending{")
                .contains("outbox_failed{")
                .contains("reconciliation_mismatch_total{")
                .contains("duplicate_trade_suppressed_total{")
                .contains("trade_reconciled_total{")
                .contains("intelligence_receipts_total{")
                .contains("deterministic_inference_total{")
                .contains("deterministic_mismatch_total{")
                .contains("trade_failed_total{")
                .contains("service=\"cryptobot-service\"");
    }

    @Test
    void rejectIsTerminal() throws Exception {
        UUID user = UUID.randomUUID();
        String token = bearer(user);
        JsonNode created = JSON.readTree(web.post().uri("/api/cryptobot/wallets").header(HttpHeaders.AUTHORIZATION, token)
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{\"address\":\"" + ADDRESS + "\",\"cluster\":\"devnet\"}")
                .exchange().expectStatus().isCreated().expectBody().returnResult().getResponseBody());
        String walletId = created.path("wallet").path("id").asText();
        JsonNode proposed = JSON.readTree(web.post().uri("/api/cryptobot/wallets/" + walletId + "/proposals").header(HttpHeaders.AUTHORIZATION, token)
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{\"targetWeights\":{\"SOL\":50}}")
                .exchange().expectStatus().isCreated().expectBody().returnResult().getResponseBody());
        String proposalId = proposed.path("proposal").path("id").asText();
        web.post().uri("/api/cryptobot/proposals/" + proposalId + "/reject").header(HttpHeaders.AUTHORIZATION, token)
                .exchange().expectStatus().isOk().expectBody().jsonPath("$.status").isEqualTo("REJECTED");
        web.post().uri("/api/cryptobot/proposals/" + proposalId + "/approve").header(HttpHeaders.AUTHORIZATION, token)
                .exchange().expectStatus().isEqualTo(409);
        web.post().uri("/api/cryptobot/proposals/" + proposalId + "/execute").header(HttpHeaders.AUTHORIZATION, token)
                .exchange().expectStatus().isEqualTo(409);
    }

    @Test
    void oversizedTradeIsBlockedByPolicyAndRecorded() throws Exception {
        String token = bearer(UUID.randomUUID());
        JsonNode created = JSON.readTree(web.post().uri("/api/cryptobot/wallets").header(HttpHeaders.AUTHORIZATION, token)
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{\"address\":\"" + ADDRESS + "\",\"cluster\":\"devnet\"}")
                .exchange().expectStatus().isCreated().expectBody().returnResult().getResponseBody());
        String walletId = created.path("wallet").path("id").asText();
        // SOL 70% → 5% sells $650: over the $500 cap and the 50% turnover cap.
        JsonNode proposed = JSON.readTree(web.post().uri("/api/cryptobot/wallets/" + walletId + "/proposals").header(HttpHeaders.AUTHORIZATION, token)
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{\"targetWeights\":{\"SOL\":5}}")
                .exchange().expectStatus().isCreated().expectBody().returnResult().getResponseBody());
        JsonNode p = proposed.path("proposal");
        assertThat(p.path("status").asText()).isEqualTo("BLOCKED_BY_POLICY");
        assertThat(p.path("policy").path("violations").toString()).contains("MAX_TRADE_USD").contains("MAX_TRADE_PCT_OF_PORTFOLIO");
        web.post().uri("/api/cryptobot/proposals/" + p.path("id").asText() + "/approve").header(HttpHeaders.AUTHORIZATION, token)
                .exchange().expectStatus().isEqualTo(409);
    }

    // ---- fakes -----------------------------------------------------------------------------

    static final class SolanaDispatcher extends Dispatcher {
        static volatile int sendCount = 0;

        @Override
        public MockResponse dispatch(RecordedRequest request) {
            try {
                JsonNode body = JSON.readTree(request.getBody().readUtf8());
                String method = body.path("method").asText();
                String result = switch (method) {
                    case "getBalance" -> "{\"context\":{\"slot\":1},\"value\":7000000000}";
                    case "getTokenAccountsByOwner" -> {
                        String program = body.path("params").get(1).path("programId").asText();
                        yield program.startsWith("Tokenkeg")
                                ? "{\"context\":{\"slot\":1},\"value\":[{\"pubkey\":\"acct\",\"account\":{\"data\":{\"parsed\":{\"info\":{\"mint\":\"" + DEVNET_USDC
                                        + "\",\"tokenAmount\":{\"amount\":\"300000000\",\"decimals\":6,\"uiAmountString\":\"300\"}}}}}}]}"
                                : "{\"context\":{\"slot\":1},\"value\":[]}";
                    }
                    case "getSignaturesForAddress" -> "[{\"signature\":\"sig1\",\"slot\":5,\"blockTime\":1700000000,\"err\":null},{\"signature\":\"sig2\",\"slot\":4,\"blockTime\":1699999000,\"err\":null}]";
                    case "getLatestBlockhash" -> "{\"context\":{\"slot\":1},\"value\":{\"blockhash\":\"So11111111111111111111111111111111111111112\",\"lastValidBlockHeight\":1000}}";
                    case "simulateTransaction" -> "{\"context\":{\"slot\":1},\"value\":{\"err\":null,\"logs\":[\"Program 11111111111111111111111111111111 invoke [1]\",\"Program 11111111111111111111111111111111 success\"],\"unitsConsumed\":150}}";
                    case "sendTransaction" -> {
                        sendCount++;
                        yield "\"never\"";
                    }
                    default -> "null";
                };
                return new MockResponse().setHeader("Content-Type", "application/json")
                        .setBody("{\"jsonrpc\":\"2.0\",\"id\":" + body.path("id").asLong() + ",\"result\":" + result + "}");
            } catch (Exception e) {
                return new MockResponse().setResponseCode(500);
            }
        }
    }

    static final class RuntimeDispatcher extends Dispatcher {
        static volatile RecordedRequest lastStart;

        @Override
        public MockResponse dispatch(RecordedRequest request) {
            String path = request.getPath();
            if ("POST".equals(request.getMethod()) && "/api/runtime/runs".equals(path)) {
                lastStart = request;
                UUID runId = UUID.randomUUID();
                return json("{\"runId\":\"" + runId + "\",\"workflowId\":\"crypto.portfolio-advisor.v1\",\"correlationId\":\"c\",\"status\":\"RUNNING\"}");
            }
            if ("GET".equals(request.getMethod()) && path != null && path.startsWith("/api/runtime/runs/")) {
                String runId = path.substring("/api/runtime/runs/".length());
                String output = "{\\\"answer\\\":\\\"SOL is 70% of your wallet; one drawdown moves everything.\\\",\\\"keyRisks\\\":[{\\\"title\\\":\\\"Concentration\\\",\\\"severity\\\":\\\"HIGH\\\",\\\"why\\\":\\\"70% > 60%\\\"}],"
                        + "\\\"suggestedActions\\\":[{\\\"action\\\":\\\"REBALANCE\\\",\\\"asset\\\":\\\"SOL\\\",\\\"targetWeightPct\\\":50,\\\"rationale\\\":\\\"halve the exposure\\\"}],\\\"confidence\\\":0.8,\\\"disclaimer\\\":\\\"not advice\\\",\\\"promptVersion\\\":\\\"crypto-portfolio-advisor-v1\\\"}";
                return json("{\"runId\":\"" + runId + "\",\"workflowId\":\"crypto.portfolio-advisor.v1\",\"status\":\"SUCCEEDED\",\"agentStages\":[{\"stageId\":\"stage-1\",\"status\":\"SUCCEEDED\",\"output\":\"" + output + "\"}],"
                        + "\"llmCalls\":[{\"stageId\":\"stage-1\",\"agentId\":\"crypto-portfolio-advisor-agent\",\"model\":\"qwen3:14b\"}]}");
            }
            return new MockResponse().setResponseCode(404);
        }

        private static MockResponse json(String body) {
            return new MockResponse().setHeader("Content-Type", "application/json").setBody(body);
        }
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
