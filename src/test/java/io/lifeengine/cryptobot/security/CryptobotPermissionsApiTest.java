package io.lifeengine.cryptobot.security;

import static org.assertj.core.api.Assertions.assertThat;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import io.lifeengine.cryptobot.CryptobotServiceApplication;
import io.lifeengine.cryptobot.infrastructure.persistence.r2dbc.MarketReviewRunRepository;
import io.lifeengine.cryptobot.testsupport.StubRepositoriesConfiguration;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

/**
 * KAN-1323 (ADR-SEC-002) with the legacy path off — what UAT and PROD run once the CryptoBot role is granted: only
 * {@code CRYPTOBOT_*} opens {@code /api/cryptobot/**}. A platform ADMIN whose token carries {@code RUNTIME_*} (auth V59)
 * and the bridge on still gets 403 everywhere; each CryptoBot level opens exactly its routes.
 */
@SpringBootTest(
        classes = {CryptobotServiceApplication.class, StubRepositoriesConfiguration.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "lifeengine.cryptobot.security.derive-runtime-authorities-from-role=true",
            "lifeengine.cryptobot.security.accept-legacy-runtime-authorities=false"
        })
@AutoConfigureWebTestClient
@ActiveProfiles("test")
class CryptobotPermissionsApiTest {

    static final String ADMIN_ROUTE = "/api/cryptobot/dead-letters";
    static final String READ_ROUTE = "/api/cryptobot/market-reviews/latest?symbols=SOLUSDT";
    static final String WRITE_ROUTE = "/api/cryptobot/wallets";
    /** A GET with no rule of its own: it falls under the general "GET /api/cryptobot/** needs CRYPTOBOT_VIEWER". */
    static final String GENERAL_READ_ROUTE = "/api/cryptobot/wallets";

    @Autowired private WebTestClient webTestClient;
    @Autowired private MarketReviewRunRepository reviews;

    @BeforeEach
    void stubs() {
        Mockito.when(reviews.findLatestBySymbol(ArgumentMatchers.anyString())).thenReturn(Mono.empty());
    }

    int status(HttpMethod method, String uri, String token) {
        WebTestClient.RequestBodySpec spec = webTestClient.method(method).uri(uri).header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        WebTestClient.RequestHeadersSpec<?> req = method == HttpMethod.POST ? spec.contentType(MediaType.APPLICATION_JSON).bodyValue("{}") : spec;
        return req.exchange().returnResult(String.class).getStatus().value();
    }

    static String token(String role, List<String> authorities) {
        SecretKey key = Keys.hmacShaKeyFor("test-jwt-secret-at-least-32-bytes-long!!".getBytes(StandardCharsets.UTF_8));
        var b = Jwts.builder()
                .subject(UUID.randomUUID().toString())
                .claim("email", "someone@test.local")
                .claim("authorities", authorities)
                .issuedAt(java.util.Date.from(Instant.now()))
                .expiration(java.util.Date.from(Instant.now().plusSeconds(300)));
        if (role != null) {
            b.claim("role", role);
        }
        return b.signWith(key).compact();
    }

    @Test
    void aPlatformAdminWithoutACryptobotRoleIsForbiddenEverywhere() {
        // what auth V59 issues to the ADMIN role, plus the bridge on: none of it counts for CryptoBot any more
        String platformAdmin = token("ADMIN", List.of("ROLE_ADMIN", "RUNTIME_ADMIN", "RUNTIME_OPERATOR", "RUNTIME_VIEWER", "AUTH:RBAC:MANAGE"));
        assertThat(status(HttpMethod.GET, ADMIN_ROUTE, platformAdmin)).isEqualTo(403);
        assertThat(status(HttpMethod.POST, WRITE_ROUTE, platformAdmin)).isEqualTo(403);
        assertThat(status(HttpMethod.GET, READ_ROUTE, platformAdmin)).isEqualTo(403);
        assertThat(status(HttpMethod.GET, GENERAL_READ_ROUTE, platformAdmin)).isEqualTo(403);
    }

    @Test
    void aViewerReadsButDoesNotWriteOrAdminister() {
        String viewer = token("USER", List.of("CRYPTOBOT_VIEWER"));
        assertThat(status(HttpMethod.GET, READ_ROUTE, viewer)).isEqualTo(200);
        assertThat(status(HttpMethod.GET, GENERAL_READ_ROUTE, viewer)).isNotIn(401, 403);
        assertThat(status(HttpMethod.POST, WRITE_ROUTE, viewer)).isEqualTo(403);
        assertThat(status(HttpMethod.GET, ADMIN_ROUTE, viewer)).isEqualTo(403);
    }

    @Test
    void anOperatorWritesButDoesNotAdminister() {
        String operator = token("USER", List.of("CRYPTOBOT_OPERATOR"));
        assertThat(status(HttpMethod.GET, READ_ROUTE, operator)).isEqualTo(200);
        // past the authorization rule: an empty body is the controller's 400, not the filter's 403
        assertThat(status(HttpMethod.POST, WRITE_ROUTE, operator)).isNotIn(401, 403);
        assertThat(status(HttpMethod.GET, ADMIN_ROUTE, operator)).isEqualTo(403);
    }

    @Test
    void aCryptobotAdminReachesTheAdminRoutes() {
        String admin = token("USER", List.of("CRYPTOBOT_ADMIN"));
        assertThat(status(HttpMethod.GET, ADMIN_ROUTE, admin)).isNotIn(401, 403);
        assertThat(status(HttpMethod.GET, READ_ROUTE, admin)).isEqualTo(200);
    }
}
