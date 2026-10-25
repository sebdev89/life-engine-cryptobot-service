package io.lifeengine.cryptobot.application.pilot;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * KAN-1249 pilot: the signer that signs swaps ({@code cryptobot.swaps.signer.*}). Empty {@code baseUrl} (default) = the
 * shared signer ({@code cryptobot.signer.*}), as before. Set = a dedicated one — the pilot's mainnet signer, with its own
 * key, cluster, caps and spend ledger — so real money never shares a key with the devnet transfers and Proof of Value
 * payouts, and those keep their signer unchanged.
 *
 * @param token that signer's SIGNER_TOKEN (a shared service token, not a key; from a Secret)
 */
@ConfigurationProperties(prefix = "cryptobot.swaps.signer")
public record SwapsSignerProperties(String baseUrl, String token, Duration timeout) {

    public SwapsSignerProperties {
        baseUrl = baseUrl == null ? "" : baseUrl.trim();
        token = token == null ? "" : token;
        timeout = timeout == null ? Duration.ofSeconds(8) : timeout;
    }

    boolean dedicated() {
        return !baseUrl.isEmpty();
    }
}
