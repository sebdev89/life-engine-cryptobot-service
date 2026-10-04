package io.lifeengine.cryptobot.observability;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import io.lifeengine.cryptobot.CryptobotServiceApplication;
import io.lifeengine.cryptobot.testsupport.StubRepositoriesConfiguration;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * Lo que de verdad sale por stdout tiene que ser JSON con los campos comunes de la plataforma
 * (el 7/7). Este test no mira el MDC ni la configuración: hace pedidos reales,
 * captura la consola y parsea las líneas. Si alguien vuelve a un patrón de texto, o saca un campo,
 * falla acá. Las líneas del demo path (proposalId/operationId/errorCode CB-POLICY-*) se prueban en
 * {@code DemoPathJsonLogTest}, que necesita el RPC y el Runtime falsos.
 */
@SpringBootTest(
        classes = {CryptobotServiceApplication.class, StubRepositoriesConfiguration.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
@TestPropertySource(
        properties = {
            "lifeengine.deployment.env=citest",
            "lifeengine.logging.format=json",
            "management.tracing.enabled=true",
            "management.tracing.sampling.probability=1.0",
            "management.otlp.tracing.export.enabled=false"
        })
class JsonLogLineTest {

    static final String TEST_HS256_KEY = "test-jwt-secret-at-least-32-bytes-long!!";
    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired private WebTestClient webTestClient;

    @Test
    @DisplayName("un token rechazado deja una línea JSON con errorCode CB-AUTH-001, requestId y la identidad del build")
    void rejectedTokenLineIsStructured(CapturedOutput output) {
        webTestClient
                .get()
                .uri("/api/cryptobot/wallets")
                .header("X-Request-Id", "req-kan573-401")
                .exchange()
                .expectStatus()
                .isUnauthorized();

        JsonNode line = lineWithMessageStarting(output, "cryptobot_jwt rejected", "req-kan573-401");

        assertThat(line.path("level").asText()).isEqualTo("WARN");
        assertThat(line.path(ErrorCode.FIELD).asText()).isEqualTo(ErrorCode.AUTH_TOKEN.code());
        assertThat(line.path(LogFields.EVENT).asText()).isEqualTo("auth_rejected");
        assertThat(line.path(LogFields.STATUS).asInt()).isEqualTo(401);
        assertThat(line.path("service").asText()).isEqualTo("cryptobot-service");
        // Logback se inicializa UNA vez por JVM, con el Environment del primer contexto de Spring que
        // arranca en el fork de surefire: el valor concreto depende del orden de los tests. Lo que
        // se verifica es que el campo existe y viene de configuración (igual que `service`).
        assertThat(line.path("env").asText()).isNotBlank();
        assertThat(line.path("logger").asText()).isNotBlank();
        assertThat(line.path("timestamp").asText()).isNotBlank();
        assertThat(line.path(BuildIdentityJsonProvider.FIELD_VERSION).asText()).isNotBlank();
        assertThat(line.path(BuildIdentityJsonProvider.FIELD_COMMIT).asText())
                .as("el commit sale de git.properties, generado por git-commit-id en este build")
                .isNotBlank()
                .isNotEqualTo(BuildIdentityJsonProvider.UNKNOWN);
        assertThat(line.path(LogContext.REQUEST_ID).asText()).isEqualTo("req-kan573-401");
        assertThat(line.path(LogContext.CORRELATION_ID).asText()).isNotBlank();
        assertThat(line.path("traceId").asText())
                .as("sin traceId no hay salto del log a la traza")
                .isNotBlank();
        assertThat(line.path("spanId").asText()).isNotBlank();
    }

    @Test
    @DisplayName("un error de API lleva event, status, operationId (la ruta) y el tenant resuelto del token")
    void apiErrorLineCarriesTenantAndOperation(CapturedOutput output) {
        UUID user = UUID.randomUUID();
        webTestClient
                .get()
                .uri("/api/cryptobot/no-existe")
                .header("Authorization", "Bearer " + tokenFor(user))
                .header("X-Request-Id", "req-kan573-404")
                .header("X-Correlation-Id", "corr-kan573")
                .exchange()
                .expectStatus()
                .isNotFound();

        JsonNode line = lineWithMessageStarting(output, "api_error", "req-kan573-404");

        assertThat(line.path(LogFields.EVENT).asText()).isEqualTo("api_error");
        assertThat(line.path(LogFields.STATUS).asInt()).isEqualTo(404);
        assertThat(line.path(ErrorCode.FIELD).asText()).isEqualTo(ErrorCode.HTTP_404.code());
        assertThat(line.path(LogFields.OPERATION_ID).asText()).isEqualTo("GET /api/cryptobot/no-existe");
        assertThat(line.path(LogContext.TENANT_ID).asText())
                .as("el tenant es el sub del token verificado (Receipts.tenantOf), resuelto server-side")
                .isEqualTo(user.toString());
        assertThat(line.path(LogContext.CORRELATION_ID).asText()).isEqualTo("corr-kan573");
        assertThat(line.path("traceId").asText()).isNotBlank();
    }

    private static String tokenFor(UUID user) {
        return Jwts.builder()
                .subject(user.toString())
                .claim("email", "operator@test.local")
                .claim("authorities", List.of("RUNTIME_OPERATOR"))
                .expiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(Keys.hmacShaKeyFor(TEST_HS256_KEY.getBytes(StandardCharsets.UTF_8)))
                .compact();
    }

    /** La línea cuyo {@code message} empieza así y cuyo {@code requestId} es el del pedido. */
    static JsonNode lineWithMessageStarting(CapturedOutput output, String prefix, String requestId) {
        Optional<JsonNode> found = jsonLines(output)
                .filter(n -> n.path("message").asText().startsWith(prefix))
                .filter(n -> requestId.equals(n.path(LogContext.REQUEST_ID).asText()))
                .findFirst();
        assertThat(found)
                .as("no salió ninguna línea JSON '%s…' con requestId=%s; stdout:%n%s", prefix, requestId, output.getOut())
                .isPresent();
        return found.get();
    }

    static java.util.stream.Stream<JsonNode> jsonLines(CapturedOutput output) {
        return output.getOut().lines().filter(l -> l.startsWith("{")).map(JsonLogLineTest::parse).filter(n -> n != null);
    }

    private static JsonNode parse(String line) {
        try {
            return JSON.readTree(line);
        } catch (Exception e) {
            return null;
        }
    }
}
