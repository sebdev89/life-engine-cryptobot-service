package io.lifeengine.cryptobot.api.controlplane;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import io.lifeengine.cryptobot.CryptobotServiceApplication;
import io.lifeengine.cryptobot.solana.rpc.SolanaRpcClient;
import io.lifeengine.cryptobot.application.chaos.ChaosSolanaRpcClient;
import io.lifeengine.cryptobot.core.reliability.DeadLetter;
import io.lifeengine.cryptobot.core.reliability.OutboxEvent;
import io.lifeengine.cryptobot.core.reliability.TradeEvents;
import io.lifeengine.cryptobot.testsupport.InMemoryControlPlaneRepositories;
import io.lifeengine.cryptobot.testsupport.StubRepositoriesConfiguration;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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

/**
 * over HTTP: the global DLQ is an admin's ({@code RUNTIME_ADMIN}); resolve and
 * requeue persist who/when/why and are one-shot; the chaos endpoint exists only when
 * {@code cryptobot.chaos.enabled=true} (here it is, as in the demo stack) and the injected RPC is
 * the {@code @Primary} {@link SolanaRpcClient}.
 */
@SpringBootTest(
        classes = {CryptobotServiceApplication.class, StubRepositoriesConfiguration.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"cryptobot.chaos.enabled=true", "cryptobot.chaos.broadcast=rpc-down", "cryptobot.chaos.shots=-1",
                "cryptobot.reliability.reconciliation.interval=7s", "cryptobot.reliability.reconciliation.grace=11s",
                "cryptobot.reliability.reconciliation.max-attempts=5", "cryptobot.reliability.reconciliation.max-retries=4"})
@AutoConfigureWebTestClient(timeout = "30s")
@ActiveProfiles("test")
class DeadLettersAndChaosApiTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired private WebTestClient web;
    @Autowired private SolanaRpcClient rpc;
    @Autowired private io.lifeengine.cryptobot.application.reliability.ReliabilityProperties reliability;

    @Test
    @DisplayName("the reconciliation knobs bind from configuration (a record with two constructors silently kept the defaults — found by the demo)")
    void reconciliationKnobsBind() {
        assertThat(reliability.reconciliation().interval()).isEqualTo(java.time.Duration.ofSeconds(7));
        assertThat(reliability.reconciliation().grace()).isEqualTo(java.time.Duration.ofSeconds(11));
        assertThat(reliability.reconciliation().maxAttempts()).isEqualTo(5);
        assertThat(reliability.reconciliation().maxRetries()).isEqualTo(4);
    }

    @BeforeEach
    void reset() {
        InMemoryControlPlaneRepositories.reset();
    }

    @Test
    @DisplayName("with cryptobot.chaos.enabled the primary RPC client is the chaos one, armed from the environment")
    void chaosClientIsPrimaryAndArmedAtStartup() throws Exception {
        assertThat(rpc).isInstanceOf(ChaosSolanaRpcClient.class);
        JsonNode state = JSON.readTree(web.get().uri("/api/cryptobot/demo/chaos").header(HttpHeaders.AUTHORIZATION, bearer(UUID.randomUUID(), true))
                .exchange().expectStatus().isOk().expectBody().returnResult().getResponseBody());
        assertThat(state.path("broadcast").asText()).isEqualTo("rpc-down");
        assertThat(state.path("shotsLeft").asInt()).isEqualTo(-1);
        assertThat(state.path("armed").asBoolean()).isTrue();

        JsonNode rearmed = JSON.readTree(web.put().uri("/api/cryptobot/demo/chaos").header(HttpHeaders.AUTHORIZATION, bearer(UUID.randomUUID(), true))
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{\"broadcast\":\"uncertain\",\"shots\":1}")
                .exchange().expectStatus().isOk().expectBody().returnResult().getResponseBody());
        assertThat(rearmed.path("broadcast").asText()).isEqualTo("uncertain");
        assertThat(rearmed.path("shotsLeft").asInt()).isEqualTo(1);

        web.put().uri("/api/cryptobot/demo/chaos").header(HttpHeaders.AUTHORIZATION, bearer(UUID.randomUUID(), true))
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{\"broadcast\":\"meteor\"}")
                .exchange().expectStatus().isBadRequest().expectBody().jsonPath("$.code").isEqualTo("INVALID_CHAOS_MODE");

        JsonNode off = JSON.readTree(web.delete().uri("/api/cryptobot/demo/chaos").header(HttpHeaders.AUTHORIZATION, bearer(UUID.randomUUID(), true))
                .exchange().expectStatus().isOk().expectBody().returnResult().getResponseBody());
        assertThat(off.path("armed").asBoolean()).isFalse();
        // An operator (no RUNTIME_ADMIN) cannot touch the fault injection.
        web.get().uri("/api/cryptobot/demo/chaos").header(HttpHeaders.AUTHORIZATION, bearer(UUID.randomUUID(), false)).exchange().expectStatus().isForbidden();
    }

    @Test
    @DisplayName("GET /dead-letters is admin-only, lists open letters newest first with the open count, and filters")
    void listIsAdminOnlyAndFilters() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID proposal = UUID.randomUUID();
        Instant t = Instant.parse("2026-09-20T10:00:00Z");
        DeadLetter a = DeadLetter.of(DeadLetter.Source.OUTBOX, UUID.randomUUID(), proposal, owner, "Outbox delivery failed after 8 attempts", Map.of("kind", "outbox"), t);
        DeadLetter b = DeadLetter.of(DeadLetter.Source.RECONCILIATION, proposal, proposal, owner, "No verdict after 20 attempts", Map.of("kind", "ambiguous"), t.plusSeconds(5));
        DeadLetter c = new DeadLetter(UUID.randomUUID(), DeadLetter.Source.OUTBOX, UUID.randomUUID(), null, owner, "old", Map.of(), t.minusSeconds(60), t, "ops", "done", DeadLetter.Outcome.RESOLVED);
        InMemoryControlPlaneRepositories.DEAD_LETTERS.addAll(List.of(a, b, c));

        web.get().uri("/api/cryptobot/dead-letters").exchange().expectStatus().isUnauthorized();
        web.get().uri("/api/cryptobot/dead-letters").header(HttpHeaders.AUTHORIZATION, bearer(owner, false)).exchange().expectStatus().isForbidden();

        JsonNode page = JSON.readTree(web.get().uri("/api/cryptobot/dead-letters").header(HttpHeaders.AUTHORIZATION, bearer(owner, true))
                .exchange().expectStatus().isOk().expectBody().returnResult().getResponseBody());
        assertThat(page.path("open").asLong()).isEqualTo(2);
        assertThat(page.path("deadLetters")).hasSize(2);
        assertThat(page.path("deadLetters").get(0).path("id").asText()).isEqualTo(b.id().toString());
        assertThat(page.path("deadLetters").get(0).path("payload").path("kind").asText()).isEqualTo("ambiguous");
        assertThat(page.path("deadLetters").get(1).path("id").asText()).isEqualTo(a.id().toString());

        JsonNode all = JSON.readTree(web.get().uri("/api/cryptobot/dead-letters?resolved=all&source=OUTBOX").header(HttpHeaders.AUTHORIZATION, bearer(owner, true))
                .exchange().expectStatus().isOk().expectBody().returnResult().getResponseBody());
        assertThat(all.path("deadLetters")).hasSize(2);
        JsonNode resolved = JSON.readTree(web.get().uri("/api/cryptobot/dead-letters?resolved=true").header(HttpHeaders.AUTHORIZATION, bearer(owner, true))
                .exchange().expectStatus().isOk().expectBody().returnResult().getResponseBody());
        assertThat(resolved.path("deadLetters")).hasSize(1);
        assertThat(resolved.path("deadLetters").get(0).path("resolvedBy").asText()).isEqualTo("ops");
        assertThat(resolved.path("deadLetters").get(0).path("outcome").asText()).isEqualTo("RESOLVED");
        JsonNode byProposal = JSON.readTree(web.get().uri("/api/cryptobot/dead-letters?proposalId=" + proposal).header(HttpHeaders.AUTHORIZATION, bearer(owner, true))
                .exchange().expectStatus().isOk().expectBody().returnResult().getResponseBody());
        assertThat(byProposal.path("deadLetters")).hasSize(2);
        web.get().uri("/api/cryptobot/dead-letters/" + UUID.randomUUID()).header(HttpHeaders.AUTHORIZATION, bearer(owner, true)).exchange().expectStatus().isNotFound();
    }

    @Test
    @DisplayName("resolve and requeue an OUTBOX letter: persisted who/when/why, audit event, one-shot (409 on replay), event back to PENDING on requeue")
    void resolveAndRequeueOutboxLetters() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID proposal = UUID.randomUUID();
        Instant t = Instant.parse("2026-09-20T10:00:00Z");
        OutboxEvent e1 = OutboxEvent.pending(OutboxEvent.AGGREGATE_PROPOSAL, proposal, owner, TradeEvents.SUBMITTED, Map.of(), t).failed("sink down");
        OutboxEvent e2 = OutboxEvent.pending(OutboxEvent.AGGREGATE_PROPOSAL, proposal, owner, TradeEvents.CONFIRMED, Map.of(), t).failed("sink down");
        InMemoryControlPlaneRepositories.OUTBOX.put(e1.id(), e1);
        InMemoryControlPlaneRepositories.OUTBOX.put(e2.id(), e2);
        DeadLetter a = DeadLetter.of(DeadLetter.Source.OUTBOX, e1.id(), proposal, owner, "Outbox delivery failed after 8 attempts", Map.of("kind", "outbox"), t);
        DeadLetter b = DeadLetter.of(DeadLetter.Source.OUTBOX, e2.id(), proposal, owner, "Outbox delivery failed after 8 attempts", Map.of("kind", "outbox"), t);
        InMemoryControlPlaneRepositories.DEAD_LETTERS.addAll(List.of(a, b));
        String admin = bearer(owner, true);

        JsonNode resolved = JSON.readTree(web.post().uri("/api/cryptobot/dead-letters/" + a.id() + "/resolve").header(HttpHeaders.AUTHORIZATION, admin)
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{\"note\":\"consumer no longer needs it\"}")
                .exchange().expectStatus().isOk().expectBody().returnResult().getResponseBody());
        assertThat(resolved.path("deadLetter").path("resolvedAt").asText()).isNotBlank();
        assertThat(resolved.path("deadLetter").path("resolvedBy").asText()).isEqualTo("admin@test.local");
        assertThat(resolved.path("deadLetter").path("resolution").asText()).isEqualTo("consumer no longer needs it");
        assertThat(resolved.path("deadLetter").path("outcome").asText()).isEqualTo("RESOLVED");
        assertThat(InMemoryControlPlaneRepositories.OUTBOX.get(e1.id()).status()).isEqualTo(OutboxEvent.Status.FAILED);
        web.post().uri("/api/cryptobot/dead-letters/" + a.id() + "/resolve").header(HttpHeaders.AUTHORIZATION, admin)
                .exchange().expectStatus().isEqualTo(409).expectBody().jsonPath("$.message").value(m -> assertThat((String) m).contains("already resolved"));

        JsonNode requeued = JSON.readTree(web.post().uri("/api/cryptobot/dead-letters/" + b.id() + "/requeue").header(HttpHeaders.AUTHORIZATION, admin)
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{\"note\":\"sink is back\"}")
                .exchange().expectStatus().isOk().expectBody().returnResult().getResponseBody());
        assertThat(requeued.path("deadLetter").path("outcome").asText()).isEqualTo("REQUEUED");
        assertThat(requeued.path("event").path("status").asText()).isEqualTo("PENDING");
        assertThat(requeued.path("event").path("attempts").asInt()).isZero();
        assertThat(InMemoryControlPlaneRepositories.OUTBOX.get(e2.id()).status()).isEqualTo(OutboxEvent.Status.PENDING);
        web.post().uri("/api/cryptobot/dead-letters/" + b.id() + "/requeue").header(HttpHeaders.AUTHORIZATION, admin).exchange().expectStatus().isEqualTo(409);

        assertThat(InMemoryControlPlaneRepositories.AUDIT).extracting(ev -> ev.eventType()).containsExactly("DEAD_LETTER_RESOLVED", "DEAD_LETTER_REQUEUED");
        assertThat(InMemoryControlPlaneRepositories.AUDIT.get(0).actor()).isEqualTo("admin@test.local");
        JsonNode page = JSON.readTree(web.get().uri("/api/cryptobot/dead-letters").header(HttpHeaders.AUTHORIZATION, admin)
                .exchange().expectStatus().isOk().expectBody().returnResult().getResponseBody());
        assertThat(page.path("open").asLong()).isZero();
        // Operators may not resolve.
        web.post().uri("/api/cryptobot/dead-letters/" + b.id() + "/resolve").header(HttpHeaders.AUTHORIZATION, bearer(owner, false)).exchange().expectStatus().isForbidden();
    }

    private static String bearer(UUID userId, boolean admin) {
        SecretKey key = Keys.hmacShaKeyFor("test-jwt-secret-at-least-32-bytes-long!!".getBytes(StandardCharsets.UTF_8));
        return "Bearer " + Jwts.builder()
                .subject(userId.toString())
                .claim("email", admin ? "admin@test.local" : "operator@test.local")
                .claim("authorities", admin ? List.of("RUNTIME_OPERATOR", "RUNTIME_ADMIN") : List.of("RUNTIME_OPERATOR"))
                .issuedAt(java.util.Date.from(Instant.now()))
                .expiration(java.util.Date.from(Instant.now().plusSeconds(300)))
                .signWith(key)
                .compact();
    }
}
