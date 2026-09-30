package io.lifeengine.cryptobot.integration.validator;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Where the independent validator lives (paper §20). {@code token} is a shared service
 * secret (never a key). With {@code enabled=false} nothing can be executed: the signer refuses
 * every request without the validator's attestation, and this service never asks for one.
 */
@ConfigurationProperties(prefix = "cryptobot.validator")
public record ValidatorProperties(boolean enabled, String baseUrl, String token, Duration timeout) {

    public ValidatorProperties {
        baseUrl = baseUrl == null || baseUrl.isBlank() ? "http://localhost:8097" : baseUrl.trim().replaceAll("/+$", "");
        token = token == null ? "" : token;
        timeout = timeout == null ? Duration.ofSeconds(8) : timeout;
    }
}
