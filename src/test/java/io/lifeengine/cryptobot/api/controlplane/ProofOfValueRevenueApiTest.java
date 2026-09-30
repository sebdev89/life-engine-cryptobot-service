package io.lifeengine.cryptobot.api.controlplane;

import static io.lifeengine.cryptobot.api.controlplane.ProofOfValueRewardApiTest.post;
import static io.lifeengine.cryptobot.api.controlplane.ProofOfValueRewardApiTest.read;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.lifeengine.cryptobot.CryptobotServiceApplication;
import io.lifeengine.cryptobot.application.oracle.PriceOracleService;
import io.lifeengine.cryptobot.testsupport.InMemoryControlPlaneRepositories;
import io.lifeengine.cryptobot.testsupport.InMemoryPovRepositories;
import io.lifeengine.cryptobot.testsupport.StubRepositoriesConfiguration;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * KAN-824 (V7 RevenueEvent) and KAN-825 (V8 Treasury) over HTTP with the real transfer pipeline of V5: an ANCHORED ValueEvent,
 * its immediate reward, then a SIMULATED revenue event of 0.05 SOL linked to it → pov/revenue-share/v1 (20 % pool, 5 % fee,
 * 75 % retained) → one attested + signed devnet transfer per wallet → REVENUE_EVENT receipt, anchored. Then the read models:
 * the event's revenueShares, the identity's rewards (immediate and revenue apart), the treasury of cryptobot-001.
 */
@SpringBootTest(
        classes = {CryptobotServiceApplication.class, StubRepositoriesConfiguration.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient(timeout = "30s")
@ActiveProfiles("test")
class ProofOfValueRevenueApiTest {

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
        ProofOfValueRewardApiTest.wire(r, rpc, signer, validator);
    }

    @BeforeEach
    void reset() {
        InMemoryControlPlaneRepositories.reset();
        InMemoryPovRepositories.reset();
        AnchorFlowTest.DevnetDispatcher.reset();
        PovRewardFakes.Signer.reset();
        PovRewardFakes.Validator.reset();
        ProofOfValueRewardApiTest.stubSolPrice(oracle);
    }

    @Test
    void simulatedRevenueIsSplitPaidAnchoredAndAccounted() throws Exception {
        scenario(web);
    }

    static String revenue(String kind, String ref, long amount, String eventId, boolean simulated) {
        return "{\"projectId\":\"cryptobot\",\"source\":{\"kind\":\"" + kind + "\",\"ref\":\"" + ref + "\"},\"amountLamports\":" + amount
                + ",\"linkedValueEventIds\":[\"" + eventId + "\"],\"simulated\":" + simulated + "}";
    }

    /** The whole V7 + V8 scenario; returns the revenue event JSON. Shared with {@code ProofOfValueRevenueApiIT} (real Postgres). */
    static JsonNode scenario(WebTestClient web) throws Exception {
        UUID user = UUID.randomUUID();
        String admin = ProofOfValueApiTest.bearer(user, List.of("RUNTIME_OPERATOR", "RUNTIME_ADMIN"));
        String operator = ProofOfValueApiTest.bearer(user, List.of("RUNTIME_OPERATOR"));
        ProofOfValueRewardApiTest.seedIdentities(web, admin);
        PovRewardFakes.Signer.BLOCKED.add(ProofOfValueRewardApiTest.CRYPTOBOT_001_WALLET);

        // A RECORDED event cannot earn revenue (422); an unknown one neither.
        JsonNode recorded = read(post(web, admin, "/api/cryptobot/value-events", ProofOfValueApiTest.event(true)).expectStatus().isCreated());
        String id = recorded.path("id").asText();
        JsonNode notAnchored = read(post(web, admin, "/api/cryptobot/revenue-events", revenue("SIMULATED", "demo-run-1", 50_000_000L, id, true))
                .expectStatus().isEqualTo(422));
        assertThat(notAnchored.path("code").asText()).isEqualTo("VALUE_EVENT_NOT_ANCHORED");
        JsonNode unknown = read(post(web, admin, "/api/cryptobot/revenue-events",
                revenue("SIMULATED", "demo-run-1", 50_000_000L, UUID.randomUUID().toString(), true)).expectStatus().isEqualTo(422));
        assertThat(unknown.path("code").asText()).isEqualTo("UNKNOWN_VALUE_EVENT");

        read(post(web, admin, "/api/cryptobot/value-events?anchor=true", ProofOfValueApiTest.event(true)).expectStatus().isOk());
        read(post(web, admin, "/api/cryptobot/value-events/" + id + "/distribute", null).expectStatus().isCreated());

        // Only RUNTIME_ADMIN; SIMULATED must say so; a PROPOSAL must exist and be EXECUTED.
        post(web, operator, "/api/cryptobot/revenue-events", revenue("SIMULATED", "demo-run-1", 50_000_000L, id, true)).expectStatus().isForbidden();
        assertThat(read(post(web, admin, "/api/cryptobot/revenue-events", revenue("SIMULATED", "demo-run-1", 50_000_000L, id, false))
                .expectStatus().isEqualTo(422)).path("code").asText()).isEqualTo("SIMULATED_SOURCE_NOT_FLAGGED");
        assertThat(read(post(web, admin, "/api/cryptobot/revenue-events", revenue("PROPOSAL", UUID.randomUUID().toString(), 50_000_000L, id, true))
                .expectStatus().isEqualTo(422)).path("code").asText()).isEqualTo("UNKNOWN_PROPOSAL");
        post(web, admin, "/api/cryptobot/revenue-events", revenue("BOGUS", "x", 50_000_000L, id, true)).expectStatus().isBadRequest();

        JsonNode r = read(post(web, admin, "/api/cryptobot/revenue-events?anchor=true", revenue("SIMULATED", "demo-run-1", 50_000_000L, id, true))
                .expectStatus().isCreated());
        assertThat(r.path("projectId").asText()).isEqualTo("cryptobot");
        assertThat(r.path("source").path("kind").asText()).isEqualTo("SIMULATED");
        assertThat(r.path("source").path("ref").asText()).isEqualTo("demo-run-1");
        assertThat(r.path("simulated").asBoolean()).isTrue();
        assertThat(r.path("amountLamports").asLong()).isEqualTo(50_000_000L);
        assertThat(r.path("policy").path("name").asText()).isEqualTo("pov/revenue-share/v1");
        assertThat(r.path("policy").path("revenueShareBps").asInt()).isEqualTo(2000);
        assertThat(r.path("policy").path("protocolFeeBps").asInt()).isEqualTo(500);
        assertThat(r.path("contributorPoolLamports").asLong()).isEqualTo(10_000_000L);
        assertThat(r.path("protocolFeeLamports").asLong()).isEqualTo(2_500_000L);
        assertThat(r.path("retainedLamports").asLong()).isEqualTo(37_500_000L);
        assertThat(r.path("status").asText()).isEqualTo("PARTIAL");
        assertThat(r.path("linkedValueEvents").get(0).path("id").asText()).isEqualTo(id);
        assertThat(r.path("linkedValueEvents").get(0).path("title").asText()).isEqualTo("Improve CryptoBot opportunity detection");
        List<String> who = new ArrayList<>();
        List<String> statuses = new ArrayList<>();
        List<Long> lamports = new ArrayList<>();
        r.path("payouts").forEach(p -> {
            who.add(p.path("identityId").asText());
            statuses.add(p.path("status").asText());
            lamports.add(p.path("lamports").asLong());
        });
        // Units 34/33/33 of a pool of 10 000 000; sebas has no wallet (UNFUNDED), cryptobot-001's wallet is refused by the signer.
        assertThat(who).containsExactly("sebas", "dev-agent-17", "cryptobot-001");
        assertThat(lamports).containsExactly(3_400_000L, 3_300_000L, 3_300_000L);
        assertThat(statuses).containsExactly("UNFUNDED", "CONFIRMED", "FAILED");
        JsonNode paid = r.path("payouts").get(1);
        assertThat(paid.path("displayName").asText()).isEqualTo("Dev Agent 17");
        assertThat(paid.path("txSignature").asText()).isNotBlank();
        assertThat(paid.path("explorerUrl").asText()).startsWith("https://explorer.solana.com/tx/");
        assertThat(r.path("anchor").path("txSignature").asText()).isNotBlank();

        // The REVENUE_EVENT receipt: child of the VALUE_EVENT receipt, simulated recorded in it.
        JsonNode receipt = read(web.get().uri("/api/cryptobot/receipts/" + r.path("receiptHash").asText()).header(HttpHeaders.AUTHORIZATION, admin)
                .exchange().expectStatus().isOk());
        assertThat(receipt.path("receipt").path("body").path("kind").asText()).isEqualTo("REVENUE_EVENT");
        assertThat(receipt.path("receipt").path("body").path("parents").get(0).asText()).isEqualTo(recorded.path("receiptHash").asText());
        assertThat(receipt.path("receipt").path("body").path("params").path("simulated").asBoolean()).isTrue();

        // Idempotent by source: 200 with the same event, nothing new signed; the same source with other content is a 409.
        int signed = PovRewardFakes.Signer.SIGNED_FOR.size();
        JsonNode again = read(post(web, admin, "/api/cryptobot/revenue-events", revenue("SIMULATED", "demo-run-1", 50_000_000L, id, true))
                .expectStatus().isOk());
        assertThat(again.path("id").asText()).isEqualTo(r.path("id").asText());
        assertThat(PovRewardFakes.Signer.SIGNED_FOR).hasSize(signed);
        post(web, admin, "/api/cryptobot/revenue-events", revenue("SIMULATED", "demo-run-1", 60_000_000L, id, true)).expectStatus().isEqualTo(409);

        // Reads.
        JsonNode list = read(web.get().uri("/api/cryptobot/revenue-events").header(HttpHeaders.AUTHORIZATION, operator).exchange().expectStatus().isOk());
        assertThat(list).hasSize(1);
        assertThat(list.get(0).path("id").asText()).isEqualTo(r.path("id").asText());
        JsonNode got = read(web.get().uri("/api/cryptobot/revenue-events/" + r.path("id").asText()).header(HttpHeaders.AUTHORIZATION, operator).exchange()
                .expectStatus().isOk());
        assertThat(got.path("payouts")).hasSize(3);
        JsonNode event = read(web.get().uri("/api/cryptobot/value-events/" + id).header(HttpHeaders.AUTHORIZATION, operator).exchange().expectStatus().isOk());
        assertThat(event.path("revenueShares")).hasSize(1);
        assertThat(event.path("revenueShares").get(0).path("revenueEventId").asText()).isEqualTo(r.path("id").asText());
        assertThat(event.path("revenueShares").get(0).path("lamports").asLong()).isEqualTo(10_000_000L);
        JsonNode dev = read(web.get().uri("/api/cryptobot/identities/dev-agent-17").header(HttpHeaders.AUTHORIZATION, operator).exchange()
                .expectStatus().isOk());
        assertThat(dev.path("rewards").path("confirmedLamports").asLong()).isEqualTo(3_300_000L);
        assertThat(dev.path("rewards").path("revenueLamports").asLong()).isEqualTo(3_300_000L);
        assertThat(dev.path("rewards").path("payouts").asLong()).isEqualTo(2L);

        // V8: cryptobot-001's accounting treasury (the configured treasury identity: it pays both the immediate reward and the revenue share).
        JsonNode t = read(web.get().uri("/api/cryptobot/treasury/cryptobot-001").header(HttpHeaders.AUTHORIZATION, operator).exchange()
                .expectStatus().isOk());
        assertThat(t.path("identityId").asText()).isEqualTo("cryptobot-001");
        assertThat(t.path("wallet").asText()).isEqualTo(ProofOfValueRewardApiTest.CRYPTOBOT_001_WALLET);
        assertThat(t.path("onChainBalanceLamports").asLong()).isEqualTo(7_000_000_000L);
        assertThat(t.path("incomeLamports").asLong()).isEqualTo(50_000_000L);
        assertThat(t.path("protocolFeeLamports").asLong()).isEqualTo(2_500_000L);
        assertThat(t.path("retainedLamports").asLong()).isEqualTo(37_500_000L);
        assertThat(t.path("contributorPayoutsLamports").asLong()).isEqualTo(6_600_000L);
        assertThat(t.path("computeCostMicroUsd").asLong()).isZero();
        assertThat(t.path("policies").path("rewardPoolLamports").asLong()).isEqualTo(10_000_000L);
        assertThat(t.path("policies").path("revenueShareBps").asInt()).isEqualTo(2000);
        assertThat(t.path("policies").path("protocolFeeBps").asInt()).isEqualTo(500);
        assertThat(t.path("policies").path("signerMaxLamports").asLong()).isEqualTo(2_000_000_000L);
        List<String> kinds = new ArrayList<>();
        t.path("recentEvents").forEach(e -> kinds.add(e.path("kind").asText()));
        assertThat(kinds).contains("VALUE", "REVENUE", "PAYOUT");
        t.path("recentEvents").forEach(e -> {
            if (!"VALUE".equals(e.path("kind").asText())) {
                assertThat(e.path("txSignature").asText()).isNotBlank();
            }
        });
        web.get().uri("/api/cryptobot/treasury/nobody").header(HttpHeaders.AUTHORIZATION, operator).exchange().expectStatus().isNotFound();

        // Another tenant sees nothing.
        String stranger = ProofOfValueApiTest.bearer(UUID.randomUUID(), List.of("RUNTIME_OPERATOR", "RUNTIME_ADMIN"));
        web.get().uri("/api/cryptobot/revenue-events/" + r.path("id").asText()).header(HttpHeaders.AUTHORIZATION, stranger).exchange()
                .expectStatus().isNotFound();
        return r;
    }
}
