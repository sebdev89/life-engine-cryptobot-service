package io.lifeengine.cryptobot.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
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
 */
@ConfigurationProperties(prefix = "lifeengine.cryptobot.security")
public record CryptobotRuntimeSecurityProperties(
        @DefaultValue("true") boolean deriveRuntimeAuthoritiesFromRole) {}
