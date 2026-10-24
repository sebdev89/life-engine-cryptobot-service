package io.lifeengine.cryptobot.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Phase-1 bridge knob, mirrored from {@code life-engine-runtime}'s
 * {@code RuntimeSecurityProperties#deriveRuntimeAuthoritiesFromRole}.
 *
 * <p>{@code life-engine-auth} does not yet seed {@code RUNTIME_VIEWER} / {@code RUNTIME_OPERATOR} /
 * {@code RUNTIME_ADMIN} permission rows (see auth's {@code db/migration/V49__auth_rbac.sql}), so
 * platform tokens carry only {@code ROLE_*} / {@code AUTH:*} authorities. Until that ships, this
 * service mints local {@code RUNTIME_*} authorities from the JWT {@code role} claim (and
 * {@code ROLE_ADMIN}) so runtime-driven HTTP calls reach {@code /api/cryptobot/**} without a
 * second auth system.
 *
 * <p>Set {@code lifeengine.cryptobot.security.derive-runtime-authorities-from-role=false} (or env
 * {@code CRYPTOBOT_DERIVE_RUNTIME_AUTHORITIES=false}) once auth seeds proper {@code RUNTIME_*}
 * permissions and grants them via {@code auth_role_permission}.
 *
 * <p>KAN-1323 (ADR-SEC-002): CryptoBot's own permissions are {@code CRYPTOBOT_VIEWER / OPERATOR / ADMIN}, seeded by
 * auth (V65) and granted per user, never to the platform ADMIN. {@code acceptLegacyRuntimeAuthorities} (env
 * {@code CRYPTOBOT_ACCEPT_LEGACY_RUNTIME_AUTHORITIES}, default true) keeps the old path during the transition:
 * {@code RUNTIME_*} — from the token (auth V59 gives them to ADMIN) or from the bridge — still counts as the matching
 * {@code CRYPTOBOT_*}. Turning it off is what actually closes the hole: turning only the bridge off does not, because
 * the token itself carries {@code RUNTIME_*}. Turn it off only once the users who need CryptoBot hold a role with
 * {@code CRYPTOBOT_*}, or they are locked out.
 */
@ConfigurationProperties(prefix = "lifeengine.cryptobot.security")
public record CryptobotRuntimeSecurityProperties(
        @DefaultValue("true") boolean deriveRuntimeAuthoritiesFromRole,
        @DefaultValue("true") boolean acceptLegacyRuntimeAuthorities) {

    @ConstructorBinding
    public CryptobotRuntimeSecurityProperties {}

    /** The shape before KAN-1323: legacy RUNTIME_* accepted. */
    public CryptobotRuntimeSecurityProperties(boolean deriveRuntimeAuthoritiesFromRole) {
        this(deriveRuntimeAuthoritiesFromRole, true);
    }
}
