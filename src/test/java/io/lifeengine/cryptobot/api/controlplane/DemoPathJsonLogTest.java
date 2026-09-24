package io.lifeengine.cryptobot.api.controlplane;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import io.lifeengine.cryptobot.CryptobotServiceApplication;
import io.lifeengine.cryptobot.observability.ErrorCode;
import io.lifeengine.cryptobot.observability.LogContext;
import io.lifeengine.cryptobot.observability.LogFields;
import io.lifeengine.cryptobot.testsupport.InMemoryControlPlaneRepositories;
import io.lifeengine.cryptobot.testsupport.StubRepositoriesConfiguration;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.crypto.SecretKey;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * Las líneas JSON del demo path (KAN-573): el rechazo de policy y el rechazo de ejecución salen con
 * su {@code errorCode} CB-POLICY-*, y con {@code proposalId} / {@code operationId} / {@code tenantId}
 * en el MDC — sin que la línea los repita — para que en Loki {@code | json | proposalId="…"} sea la
 * historia de la propuesta. Mismos fakes que {@link ControlPlaneFlowTest}.
 */
@SpringBootTest(
        classes = {CryptobotServiceApplication.class, StubRepositoriesConfiguration.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient(timeout = "30s")
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
@TestPropertySource(properties = {"lifeengine.logging.format=json"})
class DemoPathJsonLogTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static MockWebServer rpc;
    private static MockWebServer runtime;

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

    @Test
    @DisplayName("BLOCKED_BY_POLICY deja una línea CB-POLICY-001 con proposalId y tenantId en el MDC; el execute rechazado, CB-POLICY-002 con operationId")
    void policyBlockAndExecutionRefusalAreStructured(CapturedOutput output) throws Exception {
        UUID user = UUID.randomUUID();
        String token = bearer(user);
        JsonNode created = JSON.readTree(web.post().uri("/api/cryptobot/wallets").header(HttpHeaders.AUTHORIZATION, token)
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{\"address\":\"" + ControlPlaneFlowTest.ADDRESS + "\",\"cluster\":\"devnet\"}")
                .exchange().expectStatus().isCreated().expectBody().returnResult().getResponseBody());
        String walletId = created.path("wallet").path("id").asText();
        // SOL 70% → 5% sells $650: over the $500 cap and the 50% turnover cap ⇒ BLOCKED_BY_POLICY.
        JsonNode proposed = JSON.readTree(web.post().uri("/api/cryptobot/wallets/" + walletId + "/proposals").header(HttpHeaders.AUTHORIZATION, token)
                .header("X-Request-Id", "req-kan573-blocked")
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{\"targetWeights\":{\"SOL\":5}}")
                .exchange().expectStatus().isCreated().expectBody().returnResult().getResponseBody());
        String proposalId = proposed.path("proposal").path("id").asText();
        assertThat(proposed.path("proposal").path("status").asText()).isEqualTo("BLOCKED_BY_POLICY");

        JsonNode blocked = line(output, "proposal_blocked_by_policy", proposalId);
        assertThat(blocked.path("level").asText()).isEqualTo("WARN");
        assertThat(blocked.path(ErrorCode.FIELD).asText()).isEqualTo(ErrorCode.POLICY_BLOCKED.code());
        assertThat(blocked.path(LogFields.EVENT).asText()).isEqualTo("policy_blocked");
        assertThat(blocked.path(LogFields.STATUS).asText()).isEqualTo("blocked");
        assertThat(blocked.path("message").asText()).contains("MAX_TRADE_USD");
        assertThat(blocked.path(LogContext.TENANT_ID).asText()).as("tenant = owner del token, resuelto server-side").isEqualTo(user.toString());
        assertThat(blocked.path(LogContext.REQUEST_ID).asText()).isEqualTo("req-kan573-blocked");
        assertThat(blocked.path("traceId").asText()).isNotBlank();
        assertThat(blocked.path("service").asText()).isEqualTo("cryptobot-service");

        // The policy verdict line itself is an event with status, no error code: the funnel's first stop.
        JsonNode verdict = line(output, "proposal_policy ", proposalId);
        assertThat(verdict.path(LogFields.EVENT).asText()).isEqualTo("policy_evaluated");
        assertThat(verdict.path(LogFields.STATUS).asText()).isEqualTo("blocked_by_policy");
        assertThat(verdict.has(ErrorCode.FIELD)).isFalse();

        // Executing a blocked proposal: refused before anything moves, under the caller's Idempotency-Key.
        UUID operationId = UUID.randomUUID();
        web.post().uri("/api/cryptobot/proposals/" + proposalId + "/execute").header(HttpHeaders.AUTHORIZATION, token)
                .header("Idempotency-Key", operationId.toString())
                .exchange().expectStatus().isEqualTo(409);
        JsonNode refused = line(output, "execution_precondition_failed", proposalId);
        assertThat(refused.path(ErrorCode.FIELD).asText()).isEqualTo(ErrorCode.EXECUTION_PRECONDITION.code());
        assertThat(refused.path(LogFields.EVENT).asText()).isEqualTo("execution_refused");
        assertThat(refused.path(LogContext.OPERATION_ID).asText()).as("la clave de idempotencia viaja en el MDC del pipeline").isEqualTo(operationId.toString());
        assertThat(refused.path(LogContext.TENANT_ID).asText()).isEqualTo(user.toString());
    }

    /** La línea cuyo {@code message} empieza así y cuyo {@code proposalId} (MDC) es el de la propuesta. */
    private static JsonNode line(CapturedOutput output, String prefix, String proposalId) {
        Optional<JsonNode> found = output.getOut().lines()
                .filter(l -> l.startsWith("{"))
                .map(DemoPathJsonLogTest::parse)
                .filter(n -> n != null)
                .filter(n -> n.path("message").asText().startsWith(prefix))
                .filter(n -> proposalId.equals(n.path(LogContext.PROPOSAL_ID).asText()))
                .findFirst();
        assertThat(found)
                .as("no salió ninguna línea JSON '%s…' con proposalId=%s en el MDC; stdout:%n%s", prefix, proposalId, output.getOut())
                .isPresent();
        return found.get();
    }

    private static JsonNode parse(String line) {
        try {
            return JSON.readTree(line);
        } catch (Exception e) {
            return null;
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
