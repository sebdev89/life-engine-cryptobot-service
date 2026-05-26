package io.lifeengine.cryptobot.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Master switch for the JWT security filter (default ON, can be flipped for local smoke tests). */
@ConfigurationProperties("cryptobot.security")
public record CryptobotSecurityProperties(boolean enabled) {

    public CryptobotSecurityProperties {
        // record-default semantics: leave as-is; YAML controls the value.
    }
}
