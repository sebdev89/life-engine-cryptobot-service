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
 * {@link ProofOfValueRevenueApiTest}'s scenario on the REAL stores — R2DBC + Flyway V1..V15 against Postgres
 * in Testcontainers (profile {@code e2e}). Devnet, the signer and the validator are HTTP fakes; the SOL price is a mock. Checks
 * what only Postgres enforces: the REVENUE_EVENT kind passes the receipt CHECK, the revenue payouts live in pov_payout with
 * revenue_event_id (and no distribution/value event: the one-source CHECK), the split CHECK, the unique source, the links.
 *
 * <p>Opt-in ({@code *IT}): {@code ./mvnw test -Dtest=ProofOfValueRevenueApiIT}. Needs Docker.
 */
@SpringBootTest(classes = CryptobotServiceApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient(timeout = "60s")
@ActiveProfiles("e2e")
class ProofOfValueRevenueApiIT {

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
    void revenueEventIsPersistedInPostgres() throws Exception {
        AnchorFlowTest.DevnetDispatcher.reset();
        PovRewardFakes.Signer.reset();
        PovRewardFakes.Validator.reset();
        ProofOfValueRewardApiTest.stubSolPrice(oracle);

        JsonNode r = ProofOfValueRevenueApiTest.scenario(web);
        UUID id = UUID.fromString(r.path("id").asText());

        Map<String, Object> ev = db.sql("SELECT e.source_kind, e.simulated, e.amount_lamports, e.contributor_pool_lamports, e.protocol_fee_lamports,"
                + " e.retained_lamports, e.status, e.treasury_identity_id, r.kind FROM pov_revenue_event e JOIN intelligence_receipt r"
                + " ON r.receipt_hash = e.receipt_hash WHERE e.id = :id").bind("id", id).fetch().one().block();
        assertThat(ev.get("source_kind")).isEqualTo("SIMULATED");
        assertThat(ev.get("simulated")).isEqualTo(true);
        assertThat(((Number) ev.get("amount_lamports")).longValue()).isEqualTo(50_000_000L);
        assertThat(((Number) ev.get("contributor_pool_lamports")).longValue()).isEqualTo(10_000_000L);
        assertThat(((Number) ev.get("protocol_fee_lamports")).longValue()).isEqualTo(2_500_000L);
        assertThat(((Number) ev.get("retained_lamports")).longValue()).isEqualTo(37_500_000L);
        assertThat(ev.get("status")).isEqualTo("PARTIAL");
        assertThat(ev.get("treasury_identity_id")).isEqualTo("cryptobot-001");
        assertThat(ev.get("kind")).isEqualTo("REVENUE_EVENT");

        List<Map<String, Object>> rows = db.sql("SELECT identity_id, status, lamports, distribution_id, value_event_id, policy FROM pov_payout"
                        + " WHERE revenue_event_id = :id ORDER BY position").bind("id", id).fetch().all().collectList().block();
        assertThat(rows).extracting(x -> x.get("identity_id")).containsExactly("sebas", "dev-agent-17", "cryptobot-001");
        assertThat(rows).extracting(x -> x.get("status")).containsExactly("UNFUNDED", "CONFIRMED", "FAILED");
        assertThat(rows).extracting(x -> ((Number) x.get("lamports")).longValue()).containsExactly(3_400_000L, 3_300_000L, 3_300_000L);
        assertThat(rows).allSatisfy(x -> {
            assertThat(x.get("distribution_id")).isNull();
            assertThat(x.get("value_event_id")).isNull();
            assertThat(x.get("policy")).isEqualTo("pov/revenue-share/v1");
        });
        Map<String, Object> link = db.sql("SELECT value_event_id, share_lamports FROM pov_revenue_link WHERE revenue_event_id = :id").bind("id", id)
                .fetch().one().block();
        assertThat(link.get("value_event_id").toString()).isEqualTo(r.path("linkedValueEvents").get(0).path("id").asText());
        assertThat(((Number) link.get("share_lamports")).longValue()).isEqualTo(10_000_000L);
        // The V5 distribution of the same scenario keeps its shape (distribution + value event, no revenue event).
        assertThat(db.sql("SELECT COUNT(*) AS n FROM pov_payout WHERE distribution_id IS NOT NULL AND value_event_id IS NOT NULL"
                        + " AND revenue_event_id IS NULL").map((row, meta) -> ((Number) row.get("n")).longValue()).one().block()).isEqualTo(3L);
        assertThat(db.sql("SELECT COUNT(*) AS n FROM pov_revenue_event").map((row, meta) -> ((Number) row.get("n")).longValue()).one().block())
                .isEqualTo(1L);
    }
}
