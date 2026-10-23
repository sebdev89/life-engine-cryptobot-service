package io.lifeengine.cryptobot.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * KAN-1249: the token facts the pilot's swap approval relies on — iat, jti, sid, tenant (present-but-empty is NOT
 * absent) and the algorithm the signature was actually verified with — and that an unsigned token never parses.
 */
class CryptobotJwtTokenFactsTest {

    /** HMAC material generated per run (≥ 32 bytes): nothing fixed in source. */
    private static final String HMAC_MATERIAL = UUID.randomUUID() + "-" + UUID.randomUUID();

    static CryptobotJwtService hs256() {
        return new CryptobotJwtService(new CryptobotJwtProperties(HMAC_MATERIAL), new CryptobotRuntimeSecurityProperties(true), mock(JwksPublicKeyProvider.class));
    }

    static io.jsonwebtoken.JwtBuilder claims(UUID sub, Instant iat) {
        return Jwts.builder().subject(sub.toString()).id("jti-1").claim("sid", "sid-1").claim("role", "ADMIN")
                .claim("authorities", List.of("ROLE_ADMIN")).issuedAt(Date.from(iat)).expiration(Date.from(iat.plusSeconds(600)));
    }

    @Test
    void theTokenFactsAreCarriedAndAnAbsentTenantIsNotAnEmptyOne() {
        UUID sub = UUID.randomUUID();
        Instant iat = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        var key = Keys.hmacShaKeyFor(HMAC_MATERIAL.getBytes(StandardCharsets.UTF_8));

        CryptobotPrincipal noTenant = hs256().parseToken(claims(sub, iat).signWith(key).compact()).principal().orElseThrow();
        assertThat(noTenant.issuedAt()).isEqualTo(iat);
        assertThat(noTenant.jti()).isEqualTo("jti-1");
        assertThat(noTenant.sessionId()).isEqualTo("sid-1");
        assertThat(noTenant.tenantPresent()).isFalse();
        assertThat(noTenant.algorithm()).isEqualTo("HS256");

        CryptobotPrincipal emptyTenant = hs256().parseToken(claims(sub, iat).claim("tenant", "").signWith(key).compact()).principal().orElseThrow();
        assertThat(emptyTenant.tenantPresent()).isTrue();
        assertThat(emptyTenant.tenant()).isEmpty();
    }

    @Test
    void onlyATokenVerifiedThroughTheJwksIsRs256() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        KeyPair pair = gen.generateKeyPair();
        JwksPublicKeyProvider jwks = mock(JwksPublicKeyProvider.class);
        when(jwks.isConfigured()).thenReturn(true);
        when(jwks.getPublicKey(any())).thenReturn(Optional.of((java.security.interfaces.RSAPublicKey) pair.getPublic()));
        CryptobotJwtService service = new CryptobotJwtService(new CryptobotJwtProperties(""), new CryptobotRuntimeSecurityProperties(true), jwks);

        String rs = claims(UUID.randomUUID(), Instant.now()).header().keyId("k1").and().signWith(pair.getPrivate()).compact();
        assertThat(service.parseToken(rs).principal().orElseThrow().algorithm()).isEqualTo("RS256");

        // with the JWKS configured and no shared secret, an HS256 token does not parse at all
        String hs = claims(UUID.randomUUID(), Instant.now()).signWith(Keys.hmacShaKeyFor(HMAC_MATERIAL.getBytes(StandardCharsets.UTF_8))).compact();
        assertThat(service.parseToken(hs).principal()).isEmpty();
    }

    @Test
    void anUnsignedTokenNeverParses() {
        // alg=none: header and payload, empty signature
        String header = Base64.getUrlEncoder().withoutPadding().encodeToString("{\"alg\":\"none\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
        String payload = Base64.getUrlEncoder().withoutPadding().encodeToString(("{\"sub\":\"" + UUID.randomUUID() + "\",\"role\":\"ADMIN\",\"iat\":"
                + Instant.now().getEpochSecond() + ",\"exp\":" + (Instant.now().getEpochSecond() + 600) + "}").getBytes(StandardCharsets.UTF_8));
        assertThat(hs256().parseToken(header + "." + payload + ".").principal()).isEmpty();
    }
}
