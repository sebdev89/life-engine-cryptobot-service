package io.lifeengine.cryptobot.integration.signer;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Where the isolated signer lives. {@code token} is a shared service secret (never a key).
 * With {@code enabled=false} every proposal is a paper trade.
 */
@ConfigurationProperties(prefix = "cryptobot.signer")
public record SignerProperties(boolean enabled, String baseUrl, String token, Duration timeout) {

    public SignerProperties {
        baseUrl = baseUrl == null || baseUrl.isBlank() ? "http://localhost:8096" : baseUrl.trim().replaceAll("/+$", "");
        token = token == null ? "" : token;
        timeout = timeout == null ? Duration.ofSeconds(8) : timeout;
    }
}
