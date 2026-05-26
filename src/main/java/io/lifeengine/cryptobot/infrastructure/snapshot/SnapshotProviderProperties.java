package io.lifeengine.cryptobot.infrastructure.snapshot;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("cryptobot.snapshot")
public record SnapshotProviderProperties(String provider) {

    public String providerNormalized() {
        return provider == null || provider.isBlank() ? "deterministic-local" : provider;
    }
}
