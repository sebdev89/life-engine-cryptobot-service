package io.lifeengine.cryptobot.security;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Verified caller identity extracted from a life-engine JWT.
 *
 * <p>The token facts below are what a sensitive action (the pilot's swap approval, KAN-1249) checks beyond the
 * signature: {@code issuedAt} (freshness), {@code jti} (single use), {@code sessionId} ({@code sid}),
 * {@code tenantPresent}/{@code tenant} (a present-but-empty tenant is NOT an absent one) and {@code algorithm} — the
 * algorithm the signature was actually verified with ({@code RS256} through the JWKS, or {@code HS256}).
 */
public record CryptobotPrincipal(
        UUID userId,
        String email,
        String role,
        List<String> authorities,
        String rawToken,
        Instant issuedAt,
        String jti,
        String sessionId,
        boolean tenantPresent,
        String tenant,
        String algorithm) {

    public CryptobotPrincipal {
        authorities = authorities == null ? List.of() : List.copyOf(authorities);
    }

    /** Identity only (tests and callers that do not need the token facts). */
    public CryptobotPrincipal(UUID userId, String email, String role, List<String> authorities, String rawToken) {
        this(userId, email, role, authorities, rawToken, null, null, null, false, null, null);
    }
}
