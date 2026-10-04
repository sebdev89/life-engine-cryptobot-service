package io.lifeengine.cryptobot.api.controlplane;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import io.lifeengine.cryptobot.CryptobotServiceApplication;
import io.lifeengine.cryptobot.application.oracle.PriceOracleService;
import io.lifeengine.cryptobot.core.oracle.OracleConsensus;
import io.lifeengine.cryptobot.core.oracle.OracleLimits;
import io.lifeengine.cryptobot.core.oracle.OracleReading;
import io.lifeengine.cryptobot.testsupport.InMemoryControlPlaneRepositories;
import io.lifeengine.cryptobot.testsupport.InMemoryPovRepositories;
import io.lifeengine.cryptobot.testsupport.StubRepositoriesConfiguration;
import java.math.BigDecimal;
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
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

/**
 * (Proof of Value V5) over HTTP with the real transfer pipeline ({@code ExecutionService.submitTransfer}): an ANCHORED
 * ValueEvent → {@code POST /distribute?anchor=true} → per contributor wallet: fresh blockhash, simulation, the validator's
 * attestation over the payout's (I, S), the signer (a fake that really signs and enforces an allowlist), broadcast and
 * confirmation on fake devnet. In-memory stores; only the SOL price is a mock (the oracle's sources are off in this profile).
 */
@SpringBootTest(
        classes = {CryptobotServiceApplication.class, StubRepositoriesConfiguration.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient(timeout = "30s")
@ActiveProfiles("test")
class ProofOfValueRewardApiTest {

    static final String CRYPTOBOT_001_WALLET = "Cm48Eg67MfPpkpS5SKngrA587nHNgDgXjHLwfY9U81e7";

    private static MockWebServer rpc;
    private static MockWebServer signer;
    private static MockWebServer validator;

    @Autowired private WebTestClient web;
    @MockBean private PriceOracleService oracle;

    @BeforeAll
    static void startMocks() throws Exception {
        rpc = new MockWebServer();
        rpc.setDispatcher(new AnchorFlowTest.DevnetDispatcher());
        rpc.start();
        signer = new MockWebServer();
        signer.setDispatcher(new PovRewardFakes.Signer());
        signer.start();
        validator = new MockWebServer();
        validator.setDispatcher(new PovRewardFakes.Validator());
        validator.start();
    }

    @AfterAll
    static void stopMocks() throws Exception {
        rpc.shutdown();
        signer.shutdown();
        validator.shutdown();
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        wire(r, rpc, signer, validator);
    }

    /** Shared with {@code ProofOfValueRewardApiIT}. */
    static void wire(DynamicPropertyRegistry r, MockWebServer rpc, MockWebServer signer, MockWebServer validator) {
        r.add("cryptobot.solana.rpc.devnet-url", () -> "http://localhost:" + rpc.getPort());
        r.add("cryptobot.solana.rpc.mainnet-url", () -> "http://localhost:" + rpc.getPort());
        r.add("cryptobot.signer.enabled", () -> "true");
        r.add("cryptobot.signer.base-url", () -> "http://localhost:" + signer.getPort());
        r.add("cryptobot.signer.token", () -> "flow-token");
        r.add("cryptobot.validator.enabled", () -> "true");
        r.add("cryptobot.validator.base-url", () -> "http://localhost:" + validator.getPort());
        r.add("cryptobot.validator.token", () -> "validator-token");
        r.add("cryptobot.policy.execution-enabled", () -> "true");
        r.add("cryptobot.policy.authorization.enabled-strategies", () -> "REBALANCE,POV_REWARD");
        r.add("cryptobot.pov.reward.enabled", () -> "true");
        r.add("cryptobot.pov.reward.pool-lamports", () -> "10000000");
    }

    @BeforeEach
    void reset() {
        InMemoryControlPlaneRepositories.reset();
        InMemoryPovRepositories.reset();
        AnchorFlowTest.DevnetDispatcher.reset();
        PovRewardFakes.Signer.reset();
        PovRewardFakes.Validator.reset();
        stubSolPrice(oracle);
    }

    static void stubSolPrice(PriceOracleService oracle) {
        Instant now = Instant.now();
        when(oracle.read(any())).thenReturn(Mono.just(new OracleReading(now, new OracleLimits(2, 60, 100, 1_000, 300), List.of(
                new OracleConsensus("SOL", "So11111111111111111111111111111111111111112", new BigDecimal("150"), now.minusSeconds(5), List.of(), List.of(),
                        List.of(), List.of(), "sha256:" + "d".repeat(64))))));
    }

    @Test
    void anchoredEventPaysItsContributorsOnDevnet() throws Exception {
        scenario(web);
    }

    /** The whole V5 scenario; returns the distribution JSON. Shared with {@code ProofOfValueRewardApiIT} (real Postgres). */
    static JsonNode scenario(WebTestClient web) throws Exception {
        UUID user = UUID.randomUUID();
        String admin = ProofOfValueApiTest.bearer(user, List.of("RUNTIME_OPERATOR", "RUNTIME_ADMIN"));
        String operator = ProofOfValueApiTest.bearer(user, List.of("RUNTIME_OPERATOR"));
        seedIdentities(web, admin);
        // The signer does not allow cryptobot-001's wallet: that payout FAILS, the others go on.
        PovRewardFakes.Signer.BLOCKED.add(CRYPTOBOT_001_WALLET);

        // A RECORDED event cannot be distributed (409), and a RECORDED event has no distribution (404).
        JsonNode recorded = read(post(web, admin, "/api/cryptobot/value-events", ProofOfValueApiTest.event(true)).expectStatus().isCreated());
        String id = recorded.path("id").asText();
        JsonNode refused = read(post(web, admin, "/api/cryptobot/value-events/" + id + "/distribute", null).expectStatus().isEqualTo(409));
        assertThat(refused.toString()).contains("immediate reward requires an anchored ValueEvent");
        web.get().uri("/api/cryptobot/value-events/" + id + "/distribution").header(HttpHeaders.AUTHORIZATION, admin).exchange().expectStatus().isNotFound();

        JsonNode anchored = read(post(web, admin, "/api/cryptobot/value-events?anchor=true", ProofOfValueApiTest.event(true)).expectStatus().isOk());
        assertThat(anchored.path("status").asText()).isEqualTo("ANCHORED");
        assertThat(anchored.path("distribution").isNull()).isTrue();

        // Only RUNTIME_ADMIN moves funds.
        post(web, operator, "/api/cryptobot/value-events/" + id + "/distribute", null).expectStatus().isForbidden();

        JsonNode d = read(post(web, admin, "/api/cryptobot/value-events/" + id + "/distribute?anchor=true", null).expectStatus().isCreated());
        assertThat(d.path("valueEventId").asText()).isEqualTo(id);
        assertThat(d.path("policy").asText()).isEqualTo("pov/reward-pro-rata/v1");
        assertThat(d.path("poolLamports").asLong()).isEqualTo(10_000_000L);
        assertThat(d.path("status").asText()).isEqualTo("PARTIAL");
        List<String> who = new ArrayList<>();
        List<String> statuses = new ArrayList<>();
        List<Long> lamports = new ArrayList<>();
        d.path("payouts").forEach(p -> {
            who.add(p.path("identityId").asText());
            statuses.add(p.path("status").asText());
            lamports.add(p.path("lamports").asLong());
        });
        // Units 34/33/33 → 3 400 000 / 3 300 000 / 3 300 000 lamports; sebas has no wallet (UNFUNDED, not paid).
        assertThat(who).containsExactly("sebas", "dev-agent-17", "cryptobot-001");
        assertThat(lamports).containsExactly(3_400_000L, 3_300_000L, 3_300_000L);
        assertThat(statuses).containsExactly("UNFUNDED", "CONFIRMED", "FAILED");
        JsonNode paid = d.path("payouts").get(1);
        assertThat(paid.path("displayName").asText()).isEqualTo("Dev Agent 17");
        assertThat(paid.path("wallet").asText()).isEqualTo("GwMtp15arkyoxkJ6ah3R6hCab3SXCJhsSWsZ2DyTeVnW");
        assertThat(paid.path("txSignature").asText()).isNotBlank();
        assertThat(paid.path("explorerUrl").asText()).startsWith("https://explorer.solana.com/tx/" + paid.path("txSignature").asText());
        assertThat(d.path("payouts").get(2).path("error").asText()).contains("destination_not_allowed");
        assertThat(d.path("payouts").get(2).path("txSignature").isNull()).isTrue();
        assertThat(d.path("payouts").get(0).path("wallet").isNull()).isTrue();
        assertThat(d.path("confirmedLamports").asLong()).isEqualTo(3_300_000L);

        // The validator was asked, per payout, about the POV_REWARD intent; the signer signed exactly one payout.
        assertThat(PovRewardFakes.Validator.REQUESTS).hasSize(2);
        assertThat(PovRewardFakes.Validator.REQUESTS).allSatisfy(v -> {
            assertThat(v.path("intent").path("strategy_id").asText()).isEqualTo("POV_REWARD");
            assertThat(v.path("intent").path("asset").asText()).isEqualTo("SOL");
            assertThat(v.path("cluster").asText()).isEqualTo("devnet");
        });
        assertThat(PovRewardFakes.Signer.SIGNED_FOR).hasSize(1);

        // The VALUE_DISTRIBUTION receipt is a child of the VALUE_EVENT receipt, and ?anchor=true anchored it.
        String receiptHash = d.path("receiptHash").asText();
        JsonNode receipt = read(web.get().uri("/api/cryptobot/receipts/" + receiptHash).header(HttpHeaders.AUTHORIZATION, admin).exchange()
                .expectStatus().isOk());
        assertThat(receipt.path("receipt").path("body").path("kind").asText()).isEqualTo("VALUE_DISTRIBUTION");
        assertThat(receipt.path("receipt").path("body").path("parents").get(0).asText()).isEqualTo(anchored.path("receiptHash").asText());
        assertThat(d.path("anchor").path("txSignature").asText()).isNotBlank();

        // Idempotent: 200, the same distribution, nothing new signed.
        JsonNode again = read(post(web, admin, "/api/cryptobot/value-events/" + id + "/distribute", null).expectStatus().isOk());
        assertThat(again.path("id").asText()).isEqualTo(d.path("id").asText());
        assertThat(PovRewardFakes.Signer.SIGNED_FOR).hasSize(1);

        // Read models: the distribution, the event's summary, the identity's rewards.
        JsonNode got = read(web.get().uri("/api/cryptobot/value-events/" + id + "/distribution").header(HttpHeaders.AUTHORIZATION, operator).exchange()
                .expectStatus().isOk());
        assertThat(got.path("id").asText()).isEqualTo(d.path("id").asText());
        JsonNode event = read(web.get().uri("/api/cryptobot/value-events/" + id).header(HttpHeaders.AUTHORIZATION, operator).exchange().expectStatus().isOk());
        assertThat(event.path("distribution").path("status").asText()).isEqualTo("PARTIAL");
        assertThat(event.path("distribution").path("poolLamports").asLong()).isEqualTo(10_000_000L);
        assertThat(event.path("distribution").path("confirmedLamports").asLong()).isEqualTo(3_300_000L);
        JsonNode dev = read(web.get().uri("/api/cryptobot/identities/dev-agent-17").header(HttpHeaders.AUTHORIZATION, operator).exchange()
                .expectStatus().isOk());
        assertThat(dev.path("rewards").path("confirmedLamports").asLong()).isEqualTo(3_300_000L);
        assertThat(dev.path("rewards").path("payouts").asLong()).isEqualTo(1L);
        JsonNode sebas = read(web.get().uri("/api/cryptobot/identities/sebas").header(HttpHeaders.AUTHORIZATION, operator).exchange().expectStatus().isOk());
        assertThat(sebas.path("rewards").path("confirmedLamports").asLong()).isZero();
        assertThat(sebas.path("rewards").path("payouts").asLong()).isEqualTo(1L);

        // Another tenant sees nothing.
        String stranger = ProofOfValueApiTest.bearer(UUID.randomUUID(), List.of("RUNTIME_OPERATOR", "RUNTIME_ADMIN"));
        web.get().uri("/api/cryptobot/value-events/" + id + "/distribution").header(HttpHeaders.AUTHORIZATION, stranger).exchange().expectStatus().isNotFound();
        post(web, stranger, "/api/cryptobot/value-events/" + id + "/distribute", null).expectStatus().isNotFound();
        return d;
    }

    static void seedIdentities(WebTestClient web, String token) {
        for (String body : List.of(
                "{\"id\":\"sebas\",\"kind\":\"HUMAN\",\"displayName\":\"Sebastián\"}",
                "{\"id\":\"dev-agent-17\",\"kind\":\"AGENT\",\"displayName\":\"Dev Agent 17\",\"wallet\":\"GwMtp15arkyoxkJ6ah3R6hCab3SXCJhsSWsZ2DyTeVnW\",\"ownerId\":\"sebas\"}",
                "{\"id\":\"cryptobot-001\",\"kind\":\"AGENT\",\"displayName\":\"CryptoBot 001\",\"wallet\":\"" + CRYPTOBOT_001_WALLET
                        + "\",\"ownerId\":\"sebas\",\"operatorId\":\"sebas\"}")) {
            web.post().uri("/api/cryptobot/identities").header(HttpHeaders.AUTHORIZATION, token).contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body).exchange().expectStatus().isCreated();
        }
    }

    static WebTestClient.ResponseSpec post(WebTestClient web, String token, String uri, String body) {
        WebTestClient.RequestBodySpec spec = web.post().uri(uri).header(HttpHeaders.AUTHORIZATION, token);
        return body == null ? spec.exchange() : spec.contentType(MediaType.APPLICATION_JSON).bodyValue(body).exchange();
    }

    static JsonNode read(WebTestClient.ResponseSpec spec) throws Exception {
        return PovRewardFakes.JSON.readTree(spec.expectBody().returnResult().getResponseBody());
    }
}
