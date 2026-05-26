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
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Service;

/**
 * Parses life-engine HS256/HS512 JWTs issued by {@code life-engine-auth}. Verification only — this
 * service never issues tokens.
 */
@Service
public class CryptobotJwtService {

    private final SecretKey key;

    public CryptobotJwtService(CryptobotJwtProperties props) {
        String secret = props.secret() == null ? "" : props.secret();
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            throw new IllegalStateException(
                    "lifeengine.security.jwt.secret must be at least 32 UTF-8 bytes for HS256");
        }
        this.key = Keys.hmacShaKeyFor(bytes);
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
            List<String> authorities = new ArrayList<>();
            Object raw = claims.get("authorities");
            if (raw instanceof List<?> list) {
                for (Object item : list) {
                    if (item != null) {
                        authorities.add(item.toString());
                    }
                }
            }
            if (authorities.isEmpty() && role != null) {
                authorities.add("ROLE_" + role.trim().toUpperCase(Locale.ROOT));
            }
            return ParseOutcome.ok(
                    new CryptobotPrincipal(userId, email, role, List.copyOf(authorities), rawToken));
        } catch (ExpiredJwtException ex) {
            return ParseOutcome.failed("expired");
        } catch (SignatureException | MalformedJwtException ex) {
            return ParseOutcome.failed("invalid_signature");
        } catch (JwtException | IllegalArgumentException ex) {
            return ParseOutcome.failed("invalid");
        }
    }
}
