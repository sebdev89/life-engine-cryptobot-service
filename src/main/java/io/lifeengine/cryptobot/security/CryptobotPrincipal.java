package io.lifeengine.cryptobot.security;

import java.util.List;
import java.util.UUID;

/** Verified caller identity extracted from a life-engine JWT. */
public record CryptobotPrincipal(UUID userId, String email, String role, List<String> authorities, String rawToken) {

    public CryptobotPrincipal {
        authorities = authorities == null ? List.of() : List.copyOf(authorities);
    }
}
