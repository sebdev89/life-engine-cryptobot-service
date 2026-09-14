package io.lifeengine.cryptobot.adapters.solana;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Public JSON-RPC endpoints per cluster. No API keys: CryptoBot only ever uses the public read
 * surface plus {@code sendTransaction} on devnet.
 */
@ConfigurationProperties(prefix = "cryptobot.solana.rpc")
public record SolanaRpcProperties(String devnetUrl, String mainnetUrl, Duration timeout) {

    public SolanaRpcProperties {
        devnetUrl = blankTo(devnetUrl, "https://api.devnet.solana.com");
        mainnetUrl = blankTo(mainnetUrl, "https://api.mainnet-beta.solana.com");
        timeout = timeout == null ? Duration.ofSeconds(8) : timeout;
    }

    public String urlFor(SolanaCluster cluster) {
        return cluster == SolanaCluster.MAINNET_BETA ? mainnetUrl : devnetUrl;
    }

    private static String blankTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
