package io.lifeengine.cryptobot.security;

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
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * Verifies that browser preflight requests from local cryptobot-ui origins succeed and that real
 * (non-preflight) requests still require a valid JWT.
 *
 * <p>Regression coverage for the bug where {@code OPTIONS} preflight returned {@code 401} because
 * the JWT filter ran before the CORS filter. This test binds {@link WebTestClient} to the running
 * Netty server (real HTTP, not the mock {@code bindToApplicationContext} client auto-configured by
 * {@code @AutoConfigureWebTestClient}); the latter constructs requests with a path-only URI which
 * makes {@link org.springframework.web.cors.reactive.CorsUtils#isCorsRequest} throw, defeating the
 * scenario we want to cover.
 */
@SpringBootTest(
        classes = {CryptobotServiceApplication.class, StubRepositoriesConfiguration.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class CryptobotCorsTest {

    private static final String TEST_SECRET = "test-jwt-secret-at-least-32-bytes-long!!";
    private static final String UI_ORIGIN = "http://127.0.0.1:4204";
    private static final String UI_ORIGIN_LOCALHOST = "http://localhost:4204";

    @LocalServerPort
    private int port;

    private WebTestClient webTestClient;

    @BeforeEach
    void setUp() {
        this.webTestClient = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .build();
    }

    @Test
    void preflight_fromUiOrigin_succeedsWithoutAuth_andAdvertisesAuthorizationHeader() {
        webTestClient
                .options()
                .uri("/api/cryptobot/snapshots/BTCUSDT")
                .header(HttpHeaders.ORIGIN, UI_ORIGIN)
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, HttpMethod.GET.name())
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "Authorization,Content-Type")
                .exchange()
                .expectStatus()
                .value(status -> Assertions.assertTrue(
                        status == 200 || status == 204,
                        "expected preflight 200/204, was " + status))
                .expectHeader()
                .valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, UI_ORIGIN)
                .expectHeader()
                .value(
                        HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS,
                        value -> Assertions.assertTrue(
                                value.toLowerCase().contains("authorization"),
                                "expected Access-Control-Allow-Headers to include Authorization, was: "
                                        + value))
                .expectHeader()
                .value(
                        HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS,
                        value -> Assertions.assertTrue(
                                value.toUpperCase().contains("GET"),
                                "expected Access-Control-Allow-Methods to include GET, was: " + value));
    }

    @Test
    void preflight_fromLocalhostOrigin_alsoSucceeds() {
        webTestClient
                .options()
                .uri("/api/cryptobot/snapshots/BTCUSDT")
                .header(HttpHeaders.ORIGIN, UI_ORIGIN_LOCALHOST)
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, HttpMethod.GET.name())
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "Authorization")
                .exchange()
                .expectStatus()
                .value(status -> Assertions.assertTrue(
                        status == 200 || status == 204,
                        "expected preflight 200/204, was " + status))
                .expectHeader()
                .valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, UI_ORIGIN_LOCALHOST);
    }

    @Test
    void unauthenticatedGet_fromUiOrigin_stillReturns401() {
        webTestClient
                .get()
                .uri("/api/cryptobot/snapshots/BTCUSDT")
                .header(HttpHeaders.ORIGIN, UI_ORIGIN)
                .exchange()
                .expectStatus()
                .isUnauthorized();
    }

    @Test
    void authenticatedGet_fromUiOrigin_stillReturns200_andCarriesCorsAllowOrigin() {
        String token = adminPlatformToken();

        webTestClient
                .get()
                .uri("/api/cryptobot/snapshots/BTCUSDT")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .header(HttpHeaders.ORIGIN, UI_ORIGIN)
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, UI_ORIGIN);
    }

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
