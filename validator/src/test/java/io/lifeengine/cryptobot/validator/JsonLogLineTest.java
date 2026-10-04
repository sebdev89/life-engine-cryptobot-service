package io.lifeengine.cryptobot.validator;

import io.lifeengine.cryptobot.validator.observability.ErrorCode;
import io.lifeengine.cryptobot.validator.observability.LogContext;
import io.lifeengine.cryptobot.validator.observability.LogFields;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.lifeengine.cryptobot.validator.crypto.SolanaKeypair;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * Lo que de verdad sale por stdout del validador tiene que ser JSON con los campos comunes de la
 * plataforma: el veredicto lleva event/status y el proposalId del pedido en el
 * MDC; un token rechazado, errorCode CB-VALIDATOR-002; la identidad del build en cada línea.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@ExtendWith(OutputCaptureExtension.class)
@TestPropertySource(properties = {"lifeengine.deployment.env=citest", "lifeengine.logging.format=json", "management.otlp.tracing.export.enabled=false"})
class JsonLogLineTest {

    static final SolanaKeypair KEY = SolanaKeypair.generate();
    static final String POLICY_HASH = new PolicyStore(PolicyStoreTest.props(PolicyStoreTest.policy(""))).hash();
    private static final ObjectMapper JSON = new ObjectMapper();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("validator.keypair-json", () -> ValidationServiceTest.keyJson(KEY));
        r.add("validator.token", () -> "test-token");
        r.add("validator.policy.expected-hash", () -> POLICY_HASH);
    }

    @Autowired private WebTestClient web;

    @Test
    @DisplayName("un token rechazado deja una línea JSON CB-VALIDATOR-002 con requestId, traceId y la identidad del build")
    void badTokenLineIsStructured(CapturedOutput output) {
        web.get().uri("/api/validator/identity").header("X-Validator-Token", "wrong").header("X-Request-Id", "req-validator-401")
                .exchange().expectStatus().isUnauthorized();

        JsonNode line = line(output, "validator_bad_token", LogContext.REQUEST_ID, "req-validator-401");
        assertThat(line.path("level").asText()).isEqualTo("WARN");
        assertThat(line.path(ErrorCode.FIELD).asText()).isEqualTo(ErrorCode.BAD_TOKEN.code());
        assertThat(line.path(LogFields.EVENT).asText()).isEqualTo("auth_rejected");
        assertThat(line.path(LogFields.STATUS).asInt()).isEqualTo(401);
        assertThat(line.path("service").asText()).isEqualTo("cryptobot-validator");
        assertThat(line.path("env").asText()).isNotBlank();
        assertThat(line.path("version").asText()).isNotBlank();
        assertThat(line.path("commitSha").asText()).isNotBlank();
        assertThat(line.path(LogContext.CORRELATION_ID).asText()).isNotBlank();
        assertThat(line.path("traceId").asText()).isNotBlank();
        assertThat(line.path("spanId").asText()).isNotBlank();
    }

    @Test
    @DisplayName("el veredicto sale con event/status y el proposalId del pedido en el MDC; un DENY por hash de policy lleva CB-VALIDATOR-001")
    void decisionCarriesProposalIdInMdc(CapturedOutput output) {
        Map<String, Object> ok = Map.of("proposalId", "p-kan573", "policyHash", POLICY_HASH, "messageHash", "b".repeat(64),
                "intent", ValidationServiceTest.intent(), "state", ValidationServiceTest.state(), "cluster", "devnet");
        web.post().uri("/api/validator/validate").header("X-Validator-Token", "test-token").header("X-Request-Id", "req-validator-ok")
                .bodyValue(ok).exchange().expectStatus().isOk();
        JsonNode decided = line(output, "validator_decision", LogContext.PROPOSAL_ID, "p-kan573");
        assertThat(decided.path(LogFields.EVENT).asText()).isEqualTo("validation_decided");
        assertThat(decided.path(LogFields.STATUS).asText()).isEqualTo("escalate");
        assertThat(decided.has(ErrorCode.FIELD)).isFalse();
        assertThat(decided.path(LogContext.REQUEST_ID).asText()).isEqualTo("req-validator-ok");
        assertThat(decided.path("traceId").asText()).isNotBlank();

        Map<String, Object> wrongHash = Map.of("proposalId", "p-kan573-deny", "policyHash", "sha256:" + "0".repeat(64), "messageHash", "b".repeat(64),
                "intent", ValidationServiceTest.intent(), "state", ValidationServiceTest.state(), "cluster", "devnet");
        web.post().uri("/api/validator/validate").header("X-Validator-Token", "test-token")
                .bodyValue(wrongHash).exchange().expectStatus().isOk();
        JsonNode denied = line(output, "validator_denied", LogContext.PROPOSAL_ID, "p-kan573-deny");
        assertThat(denied.path(ErrorCode.FIELD).asText()).isEqualTo(ErrorCode.DENIED.code());
        assertThat(denied.path(LogFields.STATUS).asText()).isEqualTo("deny");
    }

    private static JsonNode line(CapturedOutput output, String prefix, String key, String value) {
        Optional<JsonNode> found = output.getOut().lines().filter(l -> l.startsWith("{")).map(JsonLogLineTest::parse).filter(n -> n != null)
                .filter(n -> n.path("message").asText().startsWith(prefix)).filter(n -> value.equals(n.path(key).asText())).findFirst();
        assertThat(found).as("no salió ninguna línea JSON '%s…' con %s=%s; stdout:%n%s", prefix, key, value, output.getOut()).isPresent();
        return found.get();
    }

    private static JsonNode parse(String line) {
        try {
            return JSON.readTree(line);
        } catch (Exception e) {
            return null;
        }
    }
}
