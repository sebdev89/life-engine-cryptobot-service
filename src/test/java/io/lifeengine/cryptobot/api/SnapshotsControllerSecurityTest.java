package io.lifeengine.cryptobot.api;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import io.lifeengine.cryptobot.CryptobotServiceApplication;
import io.lifeengine.cryptobot.testsupport.StubRepositoriesConfiguration;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * End-to-end-ish security test for {@code GET /api/cryptobot/snapshots/{symbol}}: with the
 * Phase-1 {@code derive-runtime-authorities-from-role} bridge on, an admin token shaped like
 * the one life-engine-auth issues today (carries only platform {@code ROLE_*}/{@code AUTH:*}
 * authorities) must reach the endpoint with a {@code 200}.
 */
@SpringBootTest(
        classes = {CryptobotServiceApplication.class, StubRepositoriesConfiguration.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@ActiveProfiles("test")
class SnapshotsControllerSecurityTest {

    private static final String TEST_SECRET = "test-jwt-secret-at-least-32-bytes-long!!";

    @Autowired private WebTestClient webTestClient;

    @Test
    void snapshot_returns401_whenNoToken() {
        webTestClient
                .get()
                .uri("/api/cryptobot/snapshots/BTCUSDT")
                .exchange()
                .expectStatus()
                .isUnauthorized();
    }

    @Test
    void snapshot_returns200_forAdminPlatformToken_viaPhase1Bridge() {
        String token = adminPlatformToken();

        webTestClient
                .get()
                .uri("/api/cryptobot/snapshots/BTCUSDT")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.symbol")
                .isEqualTo("BTCUSDT")
                .jsonPath("$.source")
                .exists()
                .jsonPath("$.price")
                .exists();
    }

    @Test
    void snapshot_returns403_forGuestToken() {
        String token = signedToken("GUEST", List.of("ROLE_GUEST"));

        webTestClient
                .get()
                .uri("/api/cryptobot/snapshots/BTCUSDT")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange()
                .expectStatus()
                .isForbidden();
    }

    /**
     * Mirrors what life-engine-auth emits today for {@code admin@life-engine.local}:
     * {@code role=ADMIN} + platform authority list, no {@code RUNTIME_*}.
     */
    private static String adminPlatformToken() {
        return signedToken("ADMIN", List.of("ROLE_ADMIN", "ROLE_USER", "AUTH:RBAC:MANAGE"));
    }

    private static String signedToken(String role, List<String> authorities) {
        SecretKey key = Keys.hmacShaKeyFor(TEST_SECRET.getBytes(StandardCharsets.UTF_8));
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(UUID.randomUUID().toString())
                .claim("email", "admin@life-engine.local")
                .claim("role", role)
                .claim("authorities", authorities)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(300)))
                .signWith(key)
                .compact();
    }
}
