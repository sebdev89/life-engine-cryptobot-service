package io.lifeengine.cryptobot.api.controlplane;

import io.lifeengine.cryptobot.security.CryptobotPrincipal;

/** The identity the control plane scopes everything by comes from the verified JWT — never from a header. */
final class Principals {

    private Principals() {}

    static CryptobotPrincipal require(CryptobotPrincipal principal) {
        if (principal == null || principal.userId() == null) {
            throw new IllegalStateException("Missing authenticated principal");
        }
        return principal;
    }

    static String actor(CryptobotPrincipal p) {
        return p.email() != null && !p.email().isBlank() ? p.email() : p.userId().toString();
    }
}
