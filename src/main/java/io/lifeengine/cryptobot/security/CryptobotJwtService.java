package io.lifeengine.cryptobot.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.security.Keys;
import io.jsonwebtoken.security.SignatureException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Service;

/**
 * Parses life-engine HS256/HS512 JWTs issued by {@code life-engine-auth}. Verification only — this
 * service never issues tokens.
 *
 * <p>Phase-1: while {@code life-engine-auth} has not yet seeded {@code RUNTIME_*} permissions,
 * the configured {@link CryptobotRuntimeSecurityProperties} bridge mints {@code RUNTIME_VIEWER /
 * RUNTIME_OPERATOR / RUNTIME_ADMIN} from the platform {@code role} claim (and {@code ROLE_ADMIN})
 * so the runtime can reach {@code /api/cryptobot/**} with a forwarded user JWT.
 */
@Service
public class CryptobotJwtService {

    static final String RUNTIME_VIEWER = "RUNTIME_VIEWER";
    static final String RUNTIME_OPERATOR = "RUNTIME_OPERATOR";
    static final String RUNTIME_ADMIN = "RUNTIME_ADMIN";

    private static final String PLATFORM_ROLE_ADMIN = "ROLE_ADMIN";

    private final SecretKey key;
    private final CryptobotRuntimeSecurityProperties runtimeSecurityProperties;

    public CryptobotJwtService(
            CryptobotJwtProperties props, CryptobotRuntimeSecurityProperties runtimeSecurityProperties) {
        String secret = props.secret() == null ? "" : props.secret();
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            throw new IllegalStateException(
                    "lifeengine.security.jwt.secret must be at least 32 UTF-8 bytes for HS256");
        }
        this.key = Keys.hmacShaKeyFor(bytes);
        this.runtimeSecurityProperties = runtimeSecurityProperties;
    }

    public record ParseOutcome(Optional<CryptobotPrincipal> principal, Optional<String> failureReason) {
        public static ParseOutcome ok(CryptobotPrincipal p) {
            return new ParseOutcome(Optional.of(p), Optional.empty());
        }

        public static ParseOutcome failed(String reason) {
            return new ParseOutcome(Optional.empty(), Optional.of(reason));
        }
    }

    public ParseOutcome parseAuthorizationHeader(String authorization) {
        if (authorization == null || authorization.isBlank()) {
            return ParseOutcome.failed("missing");
        }
        if (!authorization.regionMatches(true, 0, "Bearer ", 0, 7) || authorization.length() <= 7) {
            return ParseOutcome.failed("malformed_header");
        }
        return parseToken(authorization.substring(7).trim());
    }

    public ParseOutcome parseToken(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return ParseOutcome.failed("missing");
        }
        try {
            Claims claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(rawToken).getPayload();
            UUID userId = UUID.fromString(claims.getSubject());
            String email = claims.get("email", String.class);
            String role = claims.get("role", String.class);
            List<String> authorities = readAuthorities(claims);
            if (authorities.isEmpty() && role != null) {
                authorities.add("ROLE_" + role.trim().toUpperCase(Locale.ROOT));
            }
            List<String> effective = withDerivedRuntimeAuthorities(role, authorities);
            return ParseOutcome.ok(
                    new CryptobotPrincipal(userId, email, role, List.copyOf(effective), rawToken));
        } catch (ExpiredJwtException ex) {
            return ParseOutcome.failed("expired");
        } catch (SignatureException | MalformedJwtException ex) {
            return ParseOutcome.failed("invalid_signature");
        } catch (JwtException | IllegalArgumentException ex) {
            return ParseOutcome.failed("invalid");
        }
    }

    private static List<String> readAuthorities(Claims claims) {
        List<String> authorities = new ArrayList<>();
        Object raw = claims.get("authorities");
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                if (item != null) {
                    authorities.add(item.toString());
                }
            }
        }
        return authorities;
    }

    /**
     * Phase-1 bridge: mint {@code RUNTIME_*} authorities from the platform {@code role} claim and
     * {@code ROLE_ADMIN} so that life-engine-auth tokens (which carry only platform
     * {@code ROLE_*}/{@code AUTH:*} permissions today) can reach {@code /api/cryptobot/**}. Toggle
     * off via {@code lifeengine.cryptobot.security.derive-runtime-authorities-from-role=false}
     * once a future life-engine-auth migration seeds proper {@code RUNTIME_*} permissions.
     *
     * <p>Mapping (only applied when the flag is on; merge is additive — existing authorities
     * including any {@code ROLE_*} are preserved):
     * <ul>
     *   <li>{@code role=ADMIN} or {@code authorities} contains {@code ROLE_ADMIN}
     *       → adds {@code RUNTIME_VIEWER}, {@code RUNTIME_OPERATOR}, {@code RUNTIME_ADMIN}.</li>
     *   <li>{@code role ∈ {OPERATOR, BO_ADMIN}}
     *       → adds {@code RUNTIME_VIEWER}, {@code RUNTIME_OPERATOR}.</li>
     *   <li>{@code role ∈ {USER, VIEWER}} → adds {@code RUNTIME_VIEWER}.</li>
     *   <li>{@code role=GUEST} or unknown → no RUNTIME_* added (authenticated but 403 here).</li>
     * </ul>
     */
    private List<String> withDerivedRuntimeAuthorities(String role, List<String> existing) {
        if (!runtimeSecurityProperties.deriveRuntimeAuthoritiesFromRole()) {
            return existing;
        }
        Set<String> merged = new LinkedHashSet<>(existing);
        String roleUpper = role == null ? "" : role.trim().toUpperCase(Locale.ROOT);
        boolean adminLike = "ADMIN".equals(roleUpper) || existing.contains(PLATFORM_ROLE_ADMIN);
        if (adminLike) {
            merged.add(RUNTIME_VIEWER);
            merged.add(RUNTIME_OPERATOR);
            merged.add(RUNTIME_ADMIN);
        } else if ("OPERATOR".equals(roleUpper) || "BO_ADMIN".equals(roleUpper)) {
            merged.add(RUNTIME_VIEWER);
            merged.add(RUNTIME_OPERATOR);
        } else if ("USER".equals(roleUpper) || "VIEWER".equals(roleUpper)) {
            merged.add(RUNTIME_VIEWER);
        }
        return new ArrayList<>(merged);
    }
}
