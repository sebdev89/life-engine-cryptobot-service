package io.lifeengine.cryptobot.api.glossary;

import static org.assertj.core.api.Assertions.assertThat;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import io.lifeengine.cryptobot.CryptobotServiceApplication;
import io.lifeengine.cryptobot.testsupport.StubRepositoriesConfiguration;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import javax.crypto.SecretKey;
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
 * KAN-353 end to end: {@code POST /api/cryptobot/glossary/events} behind the real security filter
 * increments the counters that {@code /actuator/prometheus} exports — the criterion of the issue
 * ("el endpoint incrementa el contador").
 */
@SpringBootTest(
        classes = {CryptobotServiceApplication.class, StubRepositoriesConfiguration.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@ActiveProfiles("test")
class GlossaryEventsControllerTest {

    private static final String TEST_SECRET = "test-jwt-secret-at-least-32-bytes-long!!";

    @Autowired private WebTestClient web;
    @Autowired private MeterRegistry registry;

    @Test
    @DisplayName("without a token the batch is 401 and nothing is counted")
    void unauthenticatedIsRejected() {
        web.post().uri("/api/cryptobot/glossary/events")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"events\":[{\"term\":\"anon\",\"action\":\"open\"}]}")
                .exchange()
                .expectStatus().isUnauthorized();
        assertThat(registry.find("cryptobot.glossary.term").tag("term", "anon").counter()).isNull();
    }

    @Test
    @DisplayName("a batch of open/search/copy events lands on the counters and is exported by Prometheus")
    void batchIncrementsTheCounters() {
        String term = "PDA-" + UUID.randomUUID().toString().substring(0, 8);
        double searchMissBefore = counter("cryptobot.glossary.search", "hit", "false");

        web.post().uri("/api/cryptobot/glossary/events")
                .header(HttpHeaders.AUTHORIZATION, bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"events\":["
                        + "{\"term\":\"" + term + "\",\"action\":\"open\"},"
                        + "{\"term\":\"" + term + "\",\"action\":\"open\"},"
                        + "{\"term\":\"" + term + "\",\"action\":\"copy\"},"
                        + "{\"term\":\"" + term + "\",\"action\":\"search\",\"hit\":true},"
                        + "{\"action\":\"search\",\"hit\":false},"
                        + "{\"term\":\"" + term + "\",\"action\":\"nuke\"}"
                        + "]}")
                .exchange()
                .expectStatus().isAccepted()
                .expectBody()
                .jsonPath("$.accepted").isEqualTo(5)
                .jsonPath("$.rejected").isEqualTo(1);

        assertThat(counter("cryptobot.glossary.term", "term", term, "action", "open")).isEqualTo(2);
        assertThat(counter("cryptobot.glossary.term", "term", term, "action", "copy")).isEqualTo(1);
        assertThat(counter("cryptobot.glossary.term", "term", term, "action", "search")).isEqualTo(1);
        assertThat(counter("cryptobot.glossary.search", "hit", "false") - searchMissBefore).isEqualTo(1);
        assertThat(registry.find("cryptobot.glossary.term").tag("action", "nuke").counter()).isNull();

        String scrape = web.get().uri("/actuator/prometheus").exchange().expectStatus().isOk()
                .expectBody(String.class).returnResult().getResponseBody();
        assertThat(scrape)
                .contains("cryptobot_glossary_term_total{")
                .contains("term=\"" + term + "\"")
                .contains("cryptobot_glossary_search_total{");
        // never a person: the only labels on the term counter are the common tags + term + action
        assertThat(scrape.lines().filter(l -> l.startsWith("cryptobot_glossary_term_total{")).findFirst().orElseThrow())
                .doesNotContain("user").doesNotContain("tenant").doesNotContain("session");
    }

    @Test
    @DisplayName("an unreadable body or an oversized batch is a 400 with a code")
    void badBatchesAre400() {
        web.post().uri("/api/cryptobot/glossary/events")
                .header(HttpHeaders.AUTHORIZATION, bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"nope\":[]}")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.code").isEqualTo("INVALID_BATCH");

        StringBuilder big = new StringBuilder("{\"events\":[");
        for (int i = 0; i <= 100; i++) {
            big.append(i > 0 ? "," : "").append("{\"term\":\"t\",\"action\":\"open\"}");
        }
        big.append("]}");
        web.post().uri("/api/cryptobot/glossary/events")
                .header(HttpHeaders.AUTHORIZATION, bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(big.toString())
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.code").isEqualTo("BATCH_TOO_LARGE");
        assertThat(registry.find("cryptobot.glossary.term").tag("term", "t").counter()).isNull();
    }

    private double counter(String name, String... tags) {
        var c = registry.find(name).tags(tags).counter();
        return c == null ? 0 : c.count();
    }

    private static String bearer() {
        SecretKey key = Keys.hmacShaKeyFor(TEST_SECRET.getBytes(StandardCharsets.UTF_8));
        Instant now = Instant.now();
        return "Bearer " + Jwts.builder()
                .subject(UUID.randomUUID().toString())
                .claim("email", "admin@life-engine.local")
                .claim("role", "ADMIN")
                .claim("authorities", List.of("ROLE_ADMIN", "ROLE_USER"))
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(300)))
                .signWith(key)
                .compact();
    }
}
