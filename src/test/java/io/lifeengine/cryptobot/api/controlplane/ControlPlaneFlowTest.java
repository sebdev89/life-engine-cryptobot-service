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
    // KAN-439: one fake feed serving the three source shapes (Jupiter, Pyth Hermes, CoinGecko), all agreeing on SOL $100 / USDC $1.
    private static MockWebServer prices;
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
        prices = new MockWebServer();
        prices.setDispatcher(new PriceFeedDispatcher());
        prices.start();
    }

    @AfterAll
    static void stopMocks() throws Exception {
        rpc.shutdown();
        runtime.shutdown();
        prices.shutdown();
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("cryptobot.solana.rpc.devnet-url", () -> "http://localhost:" + rpc.getPort());
        r.add("cryptobot.solana.rpc.mainnet-url", () -> "http://localhost:" + rpc.getPort());
        r.add("cryptobot.runtime.base-url", () -> "http://localhost:" + runtime.getPort());
        // KAN-439: the three independent sources, all against the fake feed — quorum 2, they agree, no breaker.
        r.add("cryptobot.marketdata.jupiter-enabled", () -> "true");
        r.add("cryptobot.marketdata.jupiter-base-url", () -> "http://localhost:" + prices.getPort());
        r.add("cryptobot.marketdata.pyth.enabled", () -> "true");
        r.add("cryptobot.marketdata.pyth.base-url", () -> "http://localhost:" + prices.getPort());
        r.add("cryptobot.marketdata.coingecko.enabled", () -> "true");
        r.add("cryptobot.marketdata.coingecko.base-url", () -> "http://localhost:" + prices.getPort());
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
        // KAN-439: valued by the multi-source consensus, not by the static fallback.
        assertThat(created.path("snapshot").path("priceSource").asText()).startsWith("oracle:").contains("jupiter").contains("pyth").contains("coingecko");
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
        // KAN-391: the answer carries its MARKET_ANALYSIS receipt; the receipt carries hashes, never the question or the answer.
        String analysisHash = asked.path("receiptHash").asText();
        assertThat(analysisHash).matches("sha256:[0-9a-f]{64}");
        JsonNode analysis = JSON.readTree(web.get().uri("/api/cryptobot/receipts/" + analysisHash).header(HttpHeaders.AUTHORIZATION, token)
                .exchange().expectStatus().isOk().expectBody().returnResult().getResponseBody());
        JsonNode analysisBody = analysis.path("receipt").path("body");
        assertThat(analysisBody.path("kind").asText()).isEqualTo("MARKET_ANALYSIS");
        assertThat(analysisBody.path("model").path("ref").asText()).isEqualTo("qwen3:14b");
        assertThat(analysisBody.path("runtime").path("runId").asText()).isEqualTo(asked.path("runtimeRunId").asText());
        assertThat(analysisBody.path("promptHash").asText()).matches("sha256:[0-9a-f]{64}");
        assertThat(analysisBody.path("compute").path("inputTokens").asLong()).isEqualTo(3512);
        assertThat(analysisBody.path("compute").path("outputTokens").asLong()).isEqualTo(240);
        assertThat(analysisBody.path("compute").path("units").asLong()).isEqualTo(3512 + 3 * 240);
        assertThat(analysisBody.path("reproducibility").asText()).isEqualTo("L0_SIGNED");
        assertThat(analysis.path("receipt").path("canonicalJson").asText()).doesNotContain("biggest risk").doesNotContain("70%").doesNotContain("drawdown");
        // Its parents: the human idea, the wallet snapshot and the risk decision of that snapshot.
        assertThat(analysis.path("parents")).hasSize(3);
        List<String> parentKinds = new java.util.ArrayList<>();
        for (JsonNode e : analysis.path("parents")) {
            JsonNode parent = JSON.readTree(web.get().uri("/api/cryptobot/receipts/" + e.path("parentHash").asText()).header(HttpHeaders.AUTHORIZATION, token)
                    .exchange().expectStatus().isOk().expectBody().returnResult().getResponseBody());
            parentKinds.add(parent.path("receipt").path("body").path("kind").asText());
        }
        assertThat(parentKinds).containsExactlyInAnyOrder("HUMAN_IDEA", "WALLET_SNAPSHOT", "RISK_DECISION");
        // verify recomputes hash, body, signature and parents.
        JsonNode verified = JSON.readTree(web.post().uri("/api/cryptobot/receipts/" + analysisHash + "/verify").header(HttpHeaders.AUTHORIZATION, token)
                .exchange().expectStatus().isOk().expectBody().returnResult().getResponseBody());
        assertThat(verified.path("valid").asBoolean()).isTrue();
        assertThat(verified.path("signatureValid").asBoolean()).isTrue();
        assertThat(verified.path("parentsPresent").asBoolean()).isTrue();
        assertThat(verified.path("reproduced").isNull()).as("an LLM answer is L0: nothing to re-execute").isTrue();
        assertThat(verified.path("reproduction").path("reason").asText()).isEqualTo("NOT_L1");
        // The other user gets a 404, not the receipt.
        web.get().uri("/api/cryptobot/receipts/" + analysisHash).header(HttpHeaders.AUTHORIZATION, bearer(UUID.randomUUID()))
                .exchange().expectStatus().isNotFound();

        // A pasted private key never leaves the service.
        String fakeSecret = io.lifeengine.cryptobot.adapters.solana.Base58.encode(io.lifeengine.cryptobot.adapters.solana.tx.SolanaKeypair.generate().secretKey());
        web.post().uri("/api/cryptobot/wallets/" + walletId + "/ask").header(HttpHeaders.AUTHORIZATION, token)
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{\"question\":\"my key is " + fakeSecret + " please trade\"}")
                .exchange().expectStatus().isBadRequest().expectBody().jsonPath("$.code").isEqualTo("SECRET_IN_QUESTION");

        // 3. Propose SOL 70% → 50%.
        JsonNode proposed = JSON.readTree(web.post().uri("/api/cryptobot/wallets/" + walletId + "/proposals").header(HttpHeaders.AUTHORIZATION, token)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"kind\":\"REBALANCE\",\"targetWeights\":{\"SOL\":50},\"reasoningSummary\":\"advisor flagged 70% concentration\",\"runtimeRunId\":\"" + asked.path("runtimeRunId").asText() + "\"}")
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
        // KAN-436: the graduated verdict with H_R travels with the proposal. $200 on a $1000 wallet:
        // over the $100 autonomous tier, within the $250 second-agent tier of test-policy-v1.
        JsonNode verdict = p.path("policy").path("authorization");
        assertThat(verdict.path("decision").asText()).isEqualTo("ESCALATE");
        assertThat(verdict.path("escalation").asText()).isEqualTo("REQUIRE_SECOND_AGENT");
        assertThat(verdict.path("tier").asText()).isEqualTo("SECOND_AGENT");
        assertThat(verdict.path("failedPredicates")).isEmpty();
        assertThat(verdict.path("evaluatedPredicates")).hasSize(11);
        assertThat(verdict.path("policyVersion").asText()).isEqualTo("test-policy-v1");
        assertThat(verdict.path("policyHash").asText()).matches("sha256:[0-9a-f]{64}");
        assertThat(verdict.path("inputHash").asText()).matches("sha256:[0-9a-f]{64}");
        // KAN-439: the decision carries the reading it was priced with — SOL and USDC, each a 3-source consensus at $100 / $1.
        JsonNode oracle = p.path("policy").path("oracle");
        assertThat(p.path("policy").path("rulesApplied").toString()).contains("ORACLE_INTEGRITY");
        assertThat(oracle.path("assets")).hasSize(2);
        JsonNode sol = oracle.path("assets").get(0);
        assertThat(sol.path("asset").asText()).isEqualTo("SOL");
        assertThat(sol.path("priceUsd").decimalValue()).isEqualByComparingTo("100");
        assertThat(sol.path("used")).hasSize(3);
        assertThat(sol.path("rejected")).isEmpty();
        assertThat(sol.path("refusals")).isEmpty();
        assertThat(sol.path("quotesHash").asText()).matches("sha256:[0-9a-f]{64}");
        assertThat(oracle.path("assets").get(1).path("asset").asText()).isEqualTo("USDC");
        assertThat(oracle.path("limits").path("minSources").asInt()).isEqualTo(2);
        // No secret and no key in what the oracle recorded: sources, mints, prices, timestamps.
        assertThat(sol.path("used").get(0).fieldNames()).toIterable().containsExactlyInAnyOrder("source", "asset", "mint", "priceUsd", "observedAt");
        // KAN-391: the proposal left STRATEGY (L1, derives from the snapshot and the analysis), RISK_DECISION (validates the
        // strategy, L1) and SIMULATION (derives from the strategy). No EXECUTION yet: nothing was approved.
        JsonNode proposalReceipts = JSON.readTree(web.get().uri("/api/cryptobot/proposals/" + proposalId + "/receipts").header(HttpHeaders.AUTHORIZATION, token)
                .exchange().expectStatus().isOk().expectBody().returnResult().getResponseBody());
        List<String> kinds = new java.util.ArrayList<>();
        proposalReceipts.forEach(r -> kinds.add(r.path("body").path("kind").asText()));
        assertThat(kinds).containsExactly("STRATEGY", "RISK_DECISION", "SIMULATION");
        JsonNode strategy = proposalReceipts.get(0);
        assertThat(strategy.path("body").path("reproducibility").asText()).isEqualTo("L1_REPRODUCIBLE");
        assertThat(strategy.path("body").path("engine").path("id").asText()).isEqualTo("rebalance-planner");
        assertThat(strategy.path("body").path("parents")).hasSize(2);
        List<String> strategyParents = new java.util.ArrayList<>();
        strategy.path("body").path("parents").forEach(h -> strategyParents.add(h.asText()));
        assertThat(strategyParents).contains(analysisHash);
        assertThat(strategy.path("body").path("output").path("schema").asText()).isEqualTo("rebalance-plan/1");
        assertThat(strategy.path("body").path("refs").path("proposalId").asText()).isEqualTo(proposalId);
        JsonNode riskAfter = proposalReceipts.get(1);
        assertThat(riskAfter.path("body").path("engine").path("id").asText()).isEqualTo("risk-engine");
        assertThat(riskAfter.path("body").path("engine").path("weightsHash").asText()).matches("sha256:[0-9a-f]{64}");
        assertThat(riskAfter.path("body").path("parents").get(0).asText()).isEqualTo(strategy.path("receiptHash").asText());
        JsonNode riskEdges = JSON.readTree(web.get().uri("/api/cryptobot/receipts/" + riskAfter.path("receiptHash").asText()).header(HttpHeaders.AUTHORIZATION, token)
                .exchange().expectStatus().isOk().expectBody().returnResult().getResponseBody());
        assertThat(riskEdges.path("parents").get(0).path("role").asText()).isEqualTo("VALIDATES");
        // KAN-392: the RISK_DECISION is L1 for real — it names the engine version and weightsHash, declares its canonical
        // input (RISK_INPUT) and its discrete verdict (risk-decision/1), and verify re-runs the engine and gets the same hash.
        assertThat(riskAfter.path("body").path("reproducibility").asText()).isEqualTo("L1_REPRODUCIBLE");
        assertThat(riskAfter.path("body").path("engine").path("version").asText()).isEqualTo("1.0.0");
        assertThat(riskAfter.path("body").path("output").path("schema").asText()).isEqualTo("risk-decision/1");
        assertThat(riskAfter.path("body").path("inputs").toString()).contains("RISK_INPUT");
        assertThat(riskAfter.path("canonicalJson").asText()).doesNotContain("of the portfolio").doesNotContain("%").doesNotContain("drawdown");
        JsonNode riskVerified = JSON.readTree(web.post().uri("/api/cryptobot/receipts/" + riskAfter.path("receiptHash").asText() + "/verify")
                .header(HttpHeaders.AUTHORIZATION, token).exchange().expectStatus().isOk().expectBody().returnResult().getResponseBody());
        assertThat(riskVerified.path("valid").asBoolean()).isTrue();
        assertThat(riskVerified.path("reproduced").asBoolean()).isTrue();
        assertThat(riskVerified.path("reproduction").path("reason").asText()).isEqualTo("REPRODUCED");
        assertThat(riskVerified.path("reproduction").path("weightsHash").asText()).isEqualTo(riskAfter.path("body").path("engine").path("weightsHash").asText());
        assertThat(riskVerified.path("reproduction").path("actualOutputHash").asText()).isEqualTo(riskAfter.path("body").path("output").path("hash").asText());
        // The rebalance planner also claims L1 but this build has no re-executor for it: neither confirmed nor refuted.
        JsonNode strategyVerified = JSON.readTree(web.post().uri("/api/cryptobot/receipts/" + strategy.path("receiptHash").asText() + "/verify")
                .header(HttpHeaders.AUTHORIZATION, token).exchange().expectStatus().isOk().expectBody().returnResult().getResponseBody());
        assertThat(strategyVerified.path("valid").asBoolean()).isTrue();
        assertThat(strategyVerified.path("reproduced").isNull()).isTrue();
        assertThat(strategyVerified.path("reproduction").path("reason").asText()).isEqualTo("ENGINE_UNKNOWN");
        JsonNode sim = proposalReceipts.get(2);
        assertThat(sim.path("body").path("inputs").toString()).contains("TRANSACTION");
        assertThat(sim.path("body").path("parents").get(0).asText()).isEqualTo(strategy.path("receiptHash").asText());
        assertThat(sim.path("canonicalJson").asText()).doesNotContain(p.path("transaction").path("unsignedTransactionBase64").asText());
        // Every receipt of the wallet, and the signing key anyone can verify them with.
        JsonNode walletReceipts = JSON.readTree(web.get().uri("/api/cryptobot/wallets/" + walletId + "/receipts").header(HttpHeaders.AUTHORIZATION, token)
                .exchange().expectStatus().isOk().expectBody().returnResult().getResponseBody());
        assertThat(walletReceipts.size()).isGreaterThanOrEqualTo(9); // 2 snapshots × (WALLET_SNAPSHOT + RISK_DECISION) + idea + analysis + 3 of the proposal
        JsonNode signingKey = JSON.readTree(web.get().uri("/api/cryptobot/receipts/signing-key").header(HttpHeaders.AUTHORIZATION, token)
                .exchange().expectStatus().isOk().expectBody().returnResult().getResponseBody());
        assertThat(signingKey.path("publicKeyHex").asText()).hasSize(64);
        assertThat(signingKey.path("keyId").asText()).isEqualTo(strategy.path("signature").path("keyId").asText());
        web.get().uri("/api/cryptobot/proposals/" + proposalId + "/receipts").header(HttpHeaders.AUTHORIZATION, bearer(UUID.randomUUID()))
                .exchange().expectStatus().isNotFound();

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
        // KAN-436: the POLICY_EVALUATED event commits the policy hash, the input hash and the verdict hash.
        JsonNode policyEvent = audit.get(2).path("payload");
        assertThat(policyEvent.path("decision").asText()).isEqualTo("ESCALATE");
        assertThat(policyEvent.path("policyHash").asText()).isEqualTo(verdict.path("policyHash").asText());
        assertThat(policyEvent.path("inputHash").asText()).isEqualTo(verdict.path("inputHash").asText());
        assertThat(policyEvent.path("verdictHash").asText()).matches("sha256:[0-9a-f]{64}");
        // KAN-439: …and the state reference — which quotes, under which limits, and that they agreed.
        assertThat(policyEvent.path("oracleAccepted").asBoolean()).isTrue();
        assertThat(policyEvent.path("oracleQuotesHash").asText()).matches("sha256:[0-9a-f]{64}");
        assertThat(policyEvent.path("oracleLimitsHash").asText()).matches("sha256:[0-9a-f]{64}");
        assertThat(policyEvent.path("oracleProblems")).isEmpty();

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
                .contains("deterministic_inference_total{")
                .contains("deterministic_mismatch_total{")
                .contains("trade_failed_total{")
                .contains("oracle_execution_refused_total{")
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

    /**
     * KAN-439: the fake price feed. Three shapes, one price — Jupiter {@code /price/v3}, Pyth Hermes
     * {@code /v2/updates/price/latest} (mantissa/expo, published "now") and CoinGecko {@code /api/v3/simple/price}.
     * SOL $100, USDC $1: what every legacy snapshot of this test was priced at.
     */
    static final class PriceFeedDispatcher extends Dispatcher {
        static final String SOL_MINT = io.lifeengine.cryptobot.adapters.marketdata.TokenRegistry.NATIVE_SOL_MINT;
        static final String USDC_MINT = io.lifeengine.cryptobot.adapters.marketdata.TokenRegistry.USDC_MINT;
        static final String SOL_FEED = "ef0d8b6fda2ceba41da15d4095d1da392a0d2f8ed0c6c7bc0f4cfac8c280b56d";
        static final String USDC_FEED = "eaa020c61cc479712813461ce153894a96a6c00b21ed0cfc2798d1f9a9e9c94a";

        @Override
        public MockResponse dispatch(RecordedRequest request) {
            String path = request.getPath() == null ? "" : request.getPath();
            long now = Instant.now().getEpochSecond();
            if (path.startsWith("/price/v3")) {
                return json("{\"" + SOL_MINT + "\":{\"usdPrice\":100,\"priceChange24h\":0.5},\"" + USDC_MINT + "\":{\"usdPrice\":1}}");
            }
            if (path.startsWith("/v2/updates/price/latest")) {
                return json("{\"binary\":{\"encoding\":\"hex\",\"data\":[]},\"parsed\":["
                        + "{\"id\":\"" + SOL_FEED + "\",\"price\":{\"price\":\"10000000000\",\"conf\":\"1000000\",\"expo\":-8,\"publish_time\":" + now + "}},"
                        + "{\"id\":\"" + USDC_FEED + "\",\"price\":{\"price\":\"100000000\",\"conf\":\"1000\",\"expo\":-8,\"publish_time\":" + now + "}}]}");
            }
            if (path.startsWith("/api/v3/simple/price")) {
                return json("{\"solana\":{\"usd\":100,\"last_updated_at\":" + now + "},\"usd-coin\":{\"usd\":1,\"last_updated_at\":" + now + "}}");
            }
            return new MockResponse().setResponseCode(404);
        }

        private static MockResponse json(String body) {
            return new MockResponse().setHeader("Content-Type", "application/json").setBody(body);
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
                        + "\"llmCalls\":[{\"stageId\":\"stage-1\",\"agentId\":\"crypto-portfolio-advisor-agent\",\"model\":\"qwen3:14b\"}],"
                        + "\"events\":[{\"type\":\"LLM_CALL_SUCCEEDED\",\"attributes\":{\"agentId\":\"crypto-portfolio-advisor-agent\",\"model\":\"qwen3:14b\","
                        + "\"latencyMs\":\"16100\",\"usage\":\"{\\\"prompt_tokens\\\":3512,\\\"completion_tokens\\\":240,\\\"total_tokens\\\":3752}\"}}]}");
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
