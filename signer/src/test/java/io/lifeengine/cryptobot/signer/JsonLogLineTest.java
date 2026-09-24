package io.lifeengine.cryptobot.signer;

import io.lifeengine.cryptobot.signer.observability.ErrorCode;
import io.lifeengine.cryptobot.signer.observability.LogContext;
import io.lifeengine.cryptobot.signer.observability.LogFields;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.lifeengine.cryptobot.signer.solana.LegacyTransaction;
import io.lifeengine.cryptobot.signer.solana.SolanaKeypair;
import io.lifeengine.cryptobot.signer.solana.SystemProgram;
import java.util.List;
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
 * Lo que de verdad sale por stdout del signer tiene que ser JSON con los campos comunes de la
 * plataforma (KAN-426 / KAN-573): un rechazo lleva errorCode CB-SIGNER-*, el proposalId del pedido en
 * el MDC, requestId/traceId y la identidad del build. Pedidos reales, consola capturada, líneas parseadas.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@ExtendWith(OutputCaptureExtension.class)
@TestPropertySource(properties = {"lifeengine.deployment.env=citest", "lifeengine.logging.format=json", "management.otlp.tracing.export.enabled=false"})
class JsonLogLineTest {

    static final SolanaKeypair KEY = SolanaKeypair.generate();
    static final SolanaKeypair VALIDATOR = SolanaKeypair.generate();
    static final String VAULT = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v";
    static final String BLOCKHASH = "So11111111111111111111111111111111111111112";
    private static final ObjectMapper JSON = new ObjectMapper();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("signer.keypair-json", () -> SigningPolicyTest.keyJson(KEY));
        r.add("signer.token", () -> "test-token");
        r.add("signer.allowed-destinations", () -> VAULT);
        r.add("signer.max-lamports", () -> "1000000");
        r.add("signer.validator-public-key", VALIDATOR::publicKeyBase58);
    }

    @Autowired private WebTestClient web;

    @Test
    @DisplayName("un token rechazado deja una línea JSON CB-SIGNER-003 con requestId, traceId y la identidad del build")
    void badTokenLineIsStructured(CapturedOutput output) {
        web.get().uri("/api/signer/identity").header("X-Signer-Token", "wrong").header("X-Request-Id", "req-signer-401")
                .exchange().expectStatus().isUnauthorized();

        JsonNode line = line(output, "signer_bad_token", LogContext.REQUEST_ID, "req-signer-401");
        assertThat(line.path("level").asText()).isEqualTo("WARN");
        assertThat(line.path(ErrorCode.FIELD).asText()).isEqualTo(ErrorCode.BAD_TOKEN.code());
        assertThat(line.path(LogFields.EVENT).asText()).isEqualTo("auth_rejected");
        assertThat(line.path(LogFields.STATUS).asInt()).isEqualTo(401);
        assertThat(line.path("service").asText()).isEqualTo("cryptobot-signer");
        assertThat(line.path("env").asText()).isNotBlank();
        assertThat(line.path("version").asText()).isNotBlank();
        assertThat(line.path("commitSha").asText()).isNotBlank();
        assertThat(line.path(LogContext.CORRELATION_ID).asText()).isNotBlank();
        assertThat(line.path("traceId").asText()).isNotBlank();
        assertThat(line.path("spanId").asText()).isNotBlank();
    }

    @Test
    @DisplayName("una firma rechazada sin atestación deja CB-SIGNER-002 con el proposalId del pedido en el MDC")
    void refusedSignCarriesProposalIdInMdc(CapturedOutput output) {
        String me = KEY.publicKeyBase58();
        LegacyTransaction tx = new LegacyTransaction(me, BLOCKHASH, List.of(SystemProgram.transfer(me, VAULT, 1234L)));
        web.post().uri("/api/signer/sign").header("X-Signer-Token", "test-token").header("X-Request-Id", "req-signer-403")
                .bodyValue(Map.of("proposalId", "p-kan573", "unsignedTransactionBase64", tx.unsignedBase64(), "expectedFeePayer", me, "cluster", "devnet"))
                .exchange().expectStatus().isForbidden();

        JsonNode line = line(output, "signer_refused", LogContext.PROPOSAL_ID, "p-kan573");
        assertThat(line.path(ErrorCode.FIELD).asText()).isEqualTo(ErrorCode.ATTESTATION_REFUSED.code());
        assertThat(line.path(LogFields.EVENT).asText()).isEqualTo("sign_refused");
        assertThat(line.path(LogContext.REQUEST_ID).asText()).isEqualTo("req-signer-403");
        assertThat(line.path("traceId").asText()).isNotBlank();
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
