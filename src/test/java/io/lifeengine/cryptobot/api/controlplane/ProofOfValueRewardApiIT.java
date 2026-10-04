package io.lifeengine.cryptobot.api.controlplane;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.lifeengine.cryptobot.CryptobotServiceApplication;
import io.lifeengine.cryptobot.application.oracle.PriceOracleService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * {@link ProofOfValueRewardApiTest}'s scenario on the REAL stores — R2DBC + Flyway V1..V14 against Postgres in
 * Testcontainers (profile {@code e2e}). Devnet, the signer and the validator are HTTP fakes; the SOL price is a mock. Checks
 * what only Postgres enforces: the VALUE_DISTRIBUTION kind passes the receipt CHECK, the payout rows and their FKs, the
 * unique distribution per event, the status CHECKs.
 *
 * <p>Opt-in ({@code *IT}): {@code ./mvnw test -Dtest=ProofOfValueRewardApiIT}. Needs Docker.
 */
@SpringBootTest(classes = CryptobotServiceApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient(timeout = "60s")
@ActiveProfiles("e2e")
class ProofOfValueRewardApiIT {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
            .withDatabaseName("cryptobot_it").withUsername("cryptobot").withPassword("cryptobot");

    private static MockWebServer rpc;
    private static MockWebServer signer;
    private static MockWebServer validator;

    @Autowired private WebTestClient web;
    @Autowired private DatabaseClient db;
    @MockBean private PriceOracleService oracle;

    @DynamicPropertySource
    static void wire(DynamicPropertyRegistry r) throws Exception {
        POSTGRES.start();
        rpc = new MockWebServer();
        rpc.setDispatcher(new AnchorFlowTest.DevnetDispatcher());
        rpc.start();
        signer = new MockWebServer();
        signer.setDispatcher(new PovRewardFakes.Signer());
        signer.start();
        validator = new MockWebServer();
        validator.setDispatcher(new PovRewardFakes.Validator());
        validator.start();
        String r2dbc = "r2dbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getFirstMappedPort() + "/" + POSTGRES.getDatabaseName();
        r.add("spring.r2dbc.url", () -> r2dbc);
        r.add("spring.r2dbc.username", POSTGRES::getUsername);
        r.add("spring.r2dbc.password", POSTGRES::getPassword);
        r.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        r.add("spring.flyway.user", POSTGRES::getUsername);
        r.add("spring.flyway.password", POSTGRES::getPassword);
        r.add("cryptobot.runtime.base-url", () -> "http://localhost:0");
        r.add("lifeengine.security.jwt.secret", () -> "test-jwt-secret-at-least-32-bytes-long!!");
        ProofOfValueRewardApiTest.wire(r, rpc, signer, validator);
    }

    @AfterAll
    static void stop() throws Exception {
        rpc.shutdown();
        signer.shutdown();
        validator.shutdown();
        POSTGRES.stop();
    }

    @Test
    void distributionIsPersistedInPostgres() throws Exception {
        AnchorFlowTest.DevnetDispatcher.reset();
        PovRewardFakes.Signer.reset();
        PovRewardFakes.Validator.reset();
        ProofOfValueRewardApiTest.stubSolPrice(oracle);

        JsonNode d = ProofOfValueRewardApiTest.scenario(web);
        UUID id = UUID.fromString(d.path("id").asText());

        List<Map<String, Object>> rows = db.sql("SELECT identity_id, status, lamports, tx_signature, explorer_url, error, policy FROM pov_payout"
                        + " WHERE distribution_id = :id ORDER BY position").bind("id", id).fetch().all().collectList().block();
        assertThat(rows).extracting(r -> r.get("identity_id")).containsExactly("sebas", "dev-agent-17", "cryptobot-001");
        assertThat(rows).extracting(r -> r.get("status")).containsExactly("UNFUNDED", "CONFIRMED", "FAILED");
        assertThat(rows).extracting(r -> ((Number) r.get("lamports")).longValue()).containsExactly(3_400_000L, 3_300_000L, 3_300_000L);
        assertThat(rows.get(1).get("tx_signature")).isNotNull();
        assertThat((String) rows.get(1).get("explorer_url")).startsWith("https://explorer.solana.com/tx/");
        assertThat((String) rows.get(2).get("error")).contains("destination_not_allowed");
        assertThat(rows).extracting(r -> r.get("policy")).containsOnly("pov/reward-pro-rata/v1");

        Map<String, Object> dist = db.sql("SELECT d.status, d.pool_lamports, r.kind FROM pov_distribution d JOIN intelligence_receipt r"
                + " ON r.receipt_hash = d.receipt_hash WHERE d.id = :id").bind("id", id).fetch().one().block();
        assertThat(dist.get("status")).isEqualTo("PARTIAL");
        assertThat(((Number) dist.get("pool_lamports")).longValue()).isEqualTo(10_000_000L);
        assertThat(dist.get("kind")).isEqualTo("VALUE_DISTRIBUTION");
        assertThat(db.sql("SELECT COUNT(*) AS n FROM pov_distribution").map((row, meta) -> ((Number) row.get("n")).longValue()).one().block())
                .isEqualTo(1L);
    }
}
