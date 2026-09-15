package io.lifeengine.cryptobot.signer;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "signer")
public record SignerProperties(
        String keypairPath,
        String keypairJson,
        String token,
        String cluster,
        long maxLamports,
        List<String> allowedDestinations,
        boolean enabled) {

    public SignerProperties {
        keypairPath = keypairPath == null ? "" : keypairPath.trim();
        keypairJson = keypairJson == null ? "" : keypairJson.trim();
        token = token == null ? "" : token.trim();
        cluster = cluster == null || cluster.isBlank() ? "devnet" : cluster.trim();
        maxLamports = maxLamports <= 0 ? 2_000_000_000L : maxLamports;
        allowedDestinations = allowedDestinations == null
                ? List.of()
                : allowedDestinations.stream().map(String::trim).filter(s -> !s.isEmpty()).toList();
    }
}
