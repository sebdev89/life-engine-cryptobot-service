package io.lifeengine.cryptobot.api.controlplane;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import io.lifeengine.cryptobot.CryptobotServiceApplication;
import io.lifeengine.cryptobot.core.receipts.Digests;
import io.lifeengine.cryptobot.testsupport.InMemoryControlPlaneRepositories;
import io.lifeengine.cryptobot.testsupport.InMemoryValueEventRepository;
import io.lifeengine.cryptobot.testsupport.StubRepositoriesConfiguration;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;

/** KAN-818 over HTTP: auth required, tenant from the JWT (never the body), 201 with hash + receipt, 400 for self-acceptance, 404 across owners. */
@SpringBootTest(
        classes = {CryptobotServiceApplication.class, StubRepositoriesConfiguration.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient(timeout = "30s")
@ActiveProfiles("test")
class ValueEventsApiTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired private WebTestClient web;

    @BeforeEach
    void reset() {
        InMemoryControlPlaneRepositories.reset();
        InMemoryValueEventRepository.reset();
    }

    static String body(String acceptor, String evidence) {
        return "{\"contributor\":{\"id\":\"verticals@1\",\"kind\":\"AGENT\"},"
                + "\"agent\":{\"id\":\"verticals@1\",\"ownerId\":\"sebas\",\"modelRef\":\"claude-sonnet-5-5\"},"
                + "\"contribution\":{\"type\":\"CODE\",\"evidenceHash\":\"" + Digests.sha256(evidence) + "\",\"evidenceRef\":\"github:sebdev89/repo#pr/48\"},"
                + "\"acceptance\":{\"acceptor\":{\"id\":\"" + acceptor + "\",\"kind\":\"HUMAN\"},\"method\":\"human-approval\",\"evidenceHash\":\""
                + Digests.sha256("approval") + "\",\"evidenceRef\":\"jira:KAN-818\",\"acceptedAt\":\"2026-09-30T10:01:00Z\"},"
                + "\"occurredAt\":\"2026-09-30T10:00:00Z\",\"nonce\":\"n-1\"}";
    }

    @Test
    @DisplayName("POST → 201 with the value-event hash and its receipt; GET/verify/summary see it; another owner gets 404; no token gets 401")
    void recordReadVerify() throws Exception {
        UUID owner = UUID.randomUUID();
        web.post().uri("/api/cryptobot/value-events").contentType(MediaType.APPLICATION_JSON).bodyValue(body("sebas", "diff")).exchange()
                .expectStatus().isUnauthorized();

        JsonNode created = JSON.readTree(web.post().uri("/api/cryptobot/value-events").header(HttpHeaders.AUTHORIZATION, bearer(owner))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(body("sebas", "diff")).exchange()
                .expectStatus().isCreated().expectBody().returnResult().getResponseBody());
        String hash = created.path("event").path("valueEventHash").asText();
        assertThat(hash).matches("^sha256:[0-9a-f]{64}$");
        assertThat(created.path("event").path("tenantId").asText()).isEqualTo(owner.toString());
        assertThat(created.path("event").path("receiptHash").asText()).matches("^sha256:[0-9a-f]{64}$");
        assertThat(created.path("anchor").path("anchored").asBoolean()).isFalse();

        web.get().uri("/api/cryptobot/value-events/" + hash).header(HttpHeaders.AUTHORIZATION, bearer(owner)).exchange()
                .expectStatus().isOk().expectBody().jsonPath("$.event.contributorId").isEqualTo("verticals@1");
        web.post().uri("/api/cryptobot/value-events/" + hash + "/verify").header(HttpHeaders.AUTHORIZATION, bearer(owner)).exchange()
                .expectStatus().isOk().expectBody().jsonPath("$.valid").isEqualTo(true);
        web.get().uri("/api/cryptobot/value-events/summary").header(HttpHeaders.AUTHORIZATION, bearer(owner)).exchange()
                .expectStatus().isOk().expectBody().jsonPath("$.events").isEqualTo(1).jsonPath("$.byContributionType.CODE").isEqualTo(1);
        web.get().uri("/api/cryptobot/value-events?evidenceHash=" + Digests.sha256("diff")).header(HttpHeaders.AUTHORIZATION, bearer(owner)).exchange()
                .expectStatus().isOk().expectBody().jsonPath("$[0].valueEventHash").isEqualTo(hash);

        web.get().uri("/api/cryptobot/value-events/" + hash).header(HttpHeaders.AUTHORIZATION, bearer(UUID.randomUUID())).exchange().expectStatus().isNotFound();
    }

    @Test
    @DisplayName("an event accepted by its own contributor is a 400 INVALID_VALUE_EVENT")
    void selfAcceptanceIs400() {
        web.post().uri("/api/cryptobot/value-events").header(HttpHeaders.AUTHORIZATION, bearer(UUID.randomUUID()))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(body("verticals@1", "diff")).exchange()
                .expectStatus().isBadRequest().expectBody().jsonPath("$.code").isEqualTo("INVALID_VALUE_EVENT");
    }

    private static String bearer(UUID userId) {
        SecretKey key = Keys.hmacShaKeyFor("test-jwt-secret-at-least-32-bytes-long!!".getBytes(StandardCharsets.UTF_8));
        return "Bearer " + Jwts.builder().subject(userId.toString()).claim("email", "operator@test.local")
                .claim("authorities", List.of("RUNTIME_OPERATOR"))
                .issuedAt(java.util.Date.from(Instant.now())).expiration(java.util.Date.from(Instant.now().plusSeconds(300))).signWith(key).compact();
    }
}
