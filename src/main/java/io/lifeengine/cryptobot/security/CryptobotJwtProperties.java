package io.lifeengine.cryptobot.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Shared HMAC secret between {@code life-engine-auth} (issuer), {@code life-engine-runtime}, and
 * this service. Local default is supplied via {@code JWT_SECRET}; minimum 32 UTF-8 bytes.
 */
@ConfigurationProperties("lifeengine.security.jwt")
public record CryptobotJwtProperties(String secret) {}
