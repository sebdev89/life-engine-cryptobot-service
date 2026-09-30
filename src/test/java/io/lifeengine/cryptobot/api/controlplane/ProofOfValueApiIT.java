package io.lifeengine.cryptobot.api.controlplane;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.lifeengine.cryptobot.CryptobotServiceApplication;
import io.lifeengine.cryptobot.core.receipts.AnchorMemo;
import java.util.List;
import java.util.UUID;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * KAN-818: {@code POST /value-events?anchor=true} + {@code GET} over HTTP on the REAL stores — the
 * whole Spring context with R2DBC + Flyway (V1..V12) against Postgres in Testcontainers (profile
 * {@code e2e}, nothing stubbed inside the service). Only devnet and the signer are HTTP fakes (the
 * same as {@link AnchorFlowTest}: the signer really signs, devnet confirms then finalizes).
 *
 * <p>Opt-in ({@code *IT}): {@code ./mvnw test -Dtest=ProofOfValueApiIT}. Needs Docker.
 */
@SpringBootTest(classes = CryptobotServiceApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient(timeout = "60s")
@ActiveProfiles("e2e")
class ProofOfValueApiIT {

    static final ObjectMapper JSON = new ObjectMapper();
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
            .withDatabaseName("cryptobot_it").withUsername("cryptobot").withPassword("cryptobot");

    private static MockWebServer rpc;
    private static MockWebServer signer;

    @Autowired private WebTestClient web;
    @Autowired private DatabaseClient db;

    @DynamicPropertySource
    static void wire(DynamicPropertyRegistry r) throws Exception {
        POSTGRES.start();
        rpc = new MockWebServer();
        rpc.setDispatcher(new AnchorFlowTest.DevnetDispatcher());
        rpc.start();
        signer = new MockWebServer();
        signer.setDispatcher(new AnchorFlowTest.SignerDispatcher());
        signer.start();
        String r2dbc = "r2dbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getFirstMappedPort() + "/" + POSTGRES.getDatabaseName();
        r.add("spring.r2dbc.url", () -> r2dbc);
        r.add("spring.r2dbc.username", POSTGRES::getUsername);
        r.add("spring.r2dbc.password", POSTGRES::getPassword);
        r.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        r.add("spring.flyway.user", POSTGRES::getUsername);
        r.add("spring.flyway.password", POSTGRES::getPassword);
        r.add("cryptobot.solana.rpc.devnet-url", () -> "http://localhost:" + rpc.getPort());
        r.add("cryptobot.solana.rpc.mainnet-url", () -> "http://localhost:" + rpc.getPort());
        r.add("cryptobot.runtime.base-url", () -> "http://localhost:0");
        r.add("cryptobot.signer.enabled", () -> "true");
        r.add("cryptobot.signer.base-url", () -> "http://localhost:" + signer.getPort());
        r.add("cryptobot.signer.token", () -> "flow-token");
        r.add("cryptobot.validator.enabled", () -> "false");
        r.add("lifeengine.security.jwt.secret", () -> "test-jwt-secret-at-least-32-bytes-long!!");
    }

    @AfterAll
    static void stop() throws Exception {
        rpc.shutdown();
        signer.shutdown();
        POSTGRES.stop();
    }

    @Test
    void valueEventIsPersistedAnchoredAndReadBackFromPostgres() throws Exception {
        AnchorFlowTest.DevnetDispatcher.reset();
        String admin = ProofOfValueApiTest.bearer(UUID.randomUUID(), List.of("RUNTIME_OPERATOR", "RUNTIME_ADMIN"));
        for (String body : List.of(
                "{\"id\":\"sebas\",\"kind\":\"HUMAN\",\"displayName\":\"Sebastián\"}",
                "{\"id\":\"dev-agent-17\",\"kind\":\"AGENT\",\"displayName\":\"Dev Agent 17\",\"ownerId\":\"sebas\"}",
                "{\"id\":\"cryptobot-001\",\"kind\":\"AGENT\",\"displayName\":\"CryptoBot 001\",\"ownerId\":\"sebas\"}")) {
            web.post().uri("/api/cryptobot/identities").header(HttpHeaders.AUTHORIZATION, admin).contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body).exchange().expectStatus().isCreated();
        }

        // Refused by the AcceptancePolicy: nothing reaches Postgres.
        web.post().uri("/api/cryptobot/value-events").header(HttpHeaders.AUTHORIZATION, admin).contentType(MediaType.APPLICATION_JSON)
                .bodyValue(ProofOfValueApiTest.event(false)).exchange().expectStatus().isEqualTo(422);

        JsonNode created = JSON.readTree(web.post().uri("/api/cryptobot/value-events?anchor=true").header(HttpHeaders.AUTHORIZATION, admin)
                .contentType(MediaType.APPLICATION_JSON).bodyValue(ProofOfValueApiTest.event(true))
                .exchange().expectStatus().isCreated().expectBody().returnResult().getResponseBody());
        assertThat(created.path("status").asText()).isEqualTo("ANCHORED");
        String id = created.path("id").asText();
        String receiptHash = created.path("receiptHash").asText();
        String root = created.path("anchor").path("root").asText();
        String tx = created.path("anchor").path("txSignature").asText();
        assertThat(AnchorMemo.parse(AnchorFlowTest.DevnetDispatcher.lastMemo.get()).orElseThrow().root()).isEqualTo(root);

        // What Postgres holds: the event, three contributions (34/33/33) and the receipt stamped with the anchor.
        assertThat(db.sql("SELECT count(*) AS n FROM pov_value_event").map((r, m) -> r.get("n", Long.class)).one().block()).isEqualTo(1L);
        assertThat(db.sql("SELECT units FROM pov_contribution ORDER BY position").map((r, m) -> r.get("units", Integer.class)).all().collectList().block())
                .containsExactly(34, 33, 33);
        assertThat(db.sql("SELECT kind, anchor_tx, anchor_root FROM intelligence_receipt WHERE receipt_hash = :h").bind("h", receiptHash)
                .map((r, m) -> r.get("kind", String.class) + "|" + r.get("anchor_tx", String.class) + "|" + r.get("anchor_root", String.class)).one().block())
                .isEqualTo("VALUE_EVENT|" + tx + "|" + root);

        JsonNode read = JSON.readTree(web.get().uri("/api/cryptobot/value-events/" + id).header(HttpHeaders.AUTHORIZATION, admin)
                .exchange().expectStatus().isOk().expectBody().returnResult().getResponseBody());
        assertThat(read.path("status").asText()).isEqualTo("ANCHORED");
        assertThat(read.path("contributions").get(2).path("displayName").asText()).isEqualTo("CryptoBot 001");
        assertThat(read.path("artifact").path("commitSha").asText()).isEqualTo(ProofOfValueApiTest.COMMIT);
        assertThat(read.path("acceptance").path("stages").size()).isEqualTo(5);
        JsonNode list = JSON.readTree(web.get().uri("/api/cryptobot/value-events").header(HttpHeaders.AUTHORIZATION, admin)
                .exchange().expectStatus().isOk().expectBody().returnResult().getResponseBody());
        assertThat(list).hasSize(1);

        JsonNode proof = JSON.readTree(web.get().uri("/api/cryptobot/value-events/" + id + "/proof").header(HttpHeaders.AUTHORIZATION, admin)
                .exchange().expectStatus().isOk().expectBody().returnResult().getResponseBody());
        assertThat(proof.path("verified").asBoolean()).isTrue();
        assertThat(proof.path("receiptHash").asText()).isEqualTo(receiptHash);
        assertThat(proof.path("root").asText()).isEqualTo(root);
        assertThat(proof.path("txSignature").asText()).isEqualTo(tx);

        // The batch verifies end to end against the (fake) chain.
        web.post().uri("/api/cryptobot/anchors/" + root + "/verify").header(HttpHeaders.AUTHORIZATION, admin).exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.valid").isEqualTo(true);
    }
}
