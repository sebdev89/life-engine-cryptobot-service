package io.lifeengine.cryptobot.security;

import static org.assertj.core.api.Assertions.assertThat;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the Phase-1 {@code lifeengine.cryptobot.security.derive-runtime-authorities-from-role}
 * bridge in {@link CryptobotJwtService}. Mirrors the behaviour already shipped in
 * {@code life-engine-runtime}'s {@code RuntimeJwtService}.
 */
class CryptobotJwtServiceTest {

    private static final String TEST_SECRET = "test-jwt-secret-at-least-32-bytes-long!!";

    @Test
    void adminPlatformToken_derivesRuntimeOperator_andKeepsPlatformAuthorities() {
        CryptobotJwtService service = serviceWithBridge(true);

        String token =
                signedToken(
                        "ADMIN",
                        List.of("ROLE_ADMIN", "ROLE_USER", "AUTH:RBAC:MANAGE"));

        CryptobotPrincipal principal = parseOk(service, token);

        assertThat(principal.role()).isEqualTo("ADMIN");
        assertThat(principal.authorities())
                .as("ADMIN must derive the full RUNTIME_* triple AND keep platform authorities")
                .contains("RUNTIME_VIEWER", "RUNTIME_OPERATOR", "RUNTIME_ADMIN")
                .contains("ROLE_ADMIN", "ROLE_USER", "AUTH:RBAC:MANAGE");
    }

    @Test
    void operatorRole_derivesViewerAndOperator_butNotAdmin() {
        CryptobotJwtService service = serviceWithBridge(true);

        String token = signedToken("OPERATOR", List.of("ROLE_USER"));

        CryptobotPrincipal principal = parseOk(service, token);

        assertThat(principal.authorities())
                .contains("RUNTIME_VIEWER", "RUNTIME_OPERATOR", "ROLE_USER")
                .doesNotContain("RUNTIME_ADMIN");
    }

    @Test
    void userRole_derivesViewerOnly() {
        CryptobotJwtService service = serviceWithBridge(true);

        String token = signedToken("USER", List.of("ROLE_USER"));

        CryptobotPrincipal principal = parseOk(service, token);

        assertThat(principal.authorities())
                .contains("RUNTIME_VIEWER", "ROLE_USER")
                .doesNotContain("RUNTIME_OPERATOR", "RUNTIME_ADMIN");
    }

    @Test
    void guestOrUnknownRole_doesNotDeriveAnyRuntimeAuthority() {
        CryptobotJwtService service = serviceWithBridge(true);

        String token = signedToken("GUEST", List.of("ROLE_GUEST"));

        CryptobotPrincipal principal = parseOk(service, token);

        assertThat(principal.authorities())
                .containsExactly("ROLE_GUEST")
                .doesNotContain("RUNTIME_VIEWER", "RUNTIME_OPERATOR", "RUNTIME_ADMIN");
    }

    @Test
    void bridgeDisabled_returnsAuthoritiesAsIs() {
        CryptobotJwtService service = serviceWithBridge(false);

        String token =
                signedToken(
                        "ADMIN",
                        List.of("ROLE_ADMIN", "ROLE_USER", "AUTH:RBAC:MANAGE"));

        CryptobotPrincipal principal = parseOk(service, token);

        assertThat(principal.authorities())
                .as("With the flag off, the original authorities pass through untouched")
                .containsExactly("ROLE_ADMIN", "ROLE_USER", "AUTH:RBAC:MANAGE")
                .doesNotContain("RUNTIME_VIEWER", "RUNTIME_OPERATOR", "RUNTIME_ADMIN");
    }

    @Test
    void preExistingRuntimeOperatorToken_isPreservedRegardlessOfRole() {
        CryptobotJwtService service = serviceWithBridge(true);

        // No `role` claim, explicit RUNTIME_OPERATOR — the existing MarketReviewControllerTest path.
        String token = signedToken(null, List.of("RUNTIME_OPERATOR"));

        CryptobotPrincipal principal = parseOk(service, token);

        assertThat(principal.authorities()).containsExactly("RUNTIME_OPERATOR");
    }

    @Test
    void platformAdminAuthorityWithoutRoleClaim_stillDerivesRuntimeAuthorities() {
        CryptobotJwtService service = serviceWithBridge(true);

        // role=null but authorities contains ROLE_ADMIN → still treated as admin-like.
        String token = signedToken(null, List.of("ROLE_ADMIN"));

        CryptobotPrincipal principal = parseOk(service, token);

        assertThat(principal.authorities())
                .contains("RUNTIME_VIEWER", "RUNTIME_OPERATOR", "RUNTIME_ADMIN", "ROLE_ADMIN");
    }

    @Test
    void invalidSignature_failsWithInvalidSignatureReason() {
        CryptobotJwtService service = serviceWithBridge(true);

        SecretKey otherKey =
                Keys.hmacShaKeyFor("wrong-secret-which-is-also-32-bytes-long!".getBytes(StandardCharsets.UTF_8));
        Instant now = Instant.now();
        String token =
                Jwts.builder()
                        .subject(UUID.randomUUID().toString())
                        .claim("role", "ADMIN")
                        .issuedAt(Date.from(now))
                        .expiration(Date.from(now.plusSeconds(60)))
                        .signWith(otherKey)
                        .compact();

        CryptobotJwtService.ParseOutcome outcome = service.parseToken(token);
        assertThat(outcome.principal()).isEmpty();
        assertThat(outcome.failureReason()).contains("invalid_signature");
    }

    private static CryptobotJwtService serviceWithBridge(boolean enabled) {
        return new CryptobotJwtService(
                new CryptobotJwtProperties(TEST_SECRET),
                new CryptobotRuntimeSecurityProperties(enabled));
    }

    private static CryptobotPrincipal parseOk(CryptobotJwtService service, String token) {
        CryptobotJwtService.ParseOutcome outcome = service.parseToken(token);
        assertThat(outcome.failureReason()).as("expected successful parse").isEmpty();
        return outcome.principal().orElseThrow();
    }

    private static String signedToken(String role, List<String> authorities) {
        SecretKey key = Keys.hmacShaKeyFor(TEST_SECRET.getBytes(StandardCharsets.UTF_8));
        Instant now = Instant.now();
        var builder =
                Jwts.builder()
                        .subject(UUID.randomUUID().toString())
                        .claim("email", "operator@test.local")
                        .claim("authorities", authorities)
                        .issuedAt(Date.from(now))
                        .expiration(Date.from(now.plusSeconds(300)));
        if (role != null) {
            builder.claim("role", role);
        }
        return builder.signWith(key).compact();
    }
}
