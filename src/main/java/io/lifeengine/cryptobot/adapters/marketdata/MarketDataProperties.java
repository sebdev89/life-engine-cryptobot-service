package io.lifeengine.cryptobot.adapters.marketdata;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Price oracle configuration. Jupiter's lite API needs no key; when it is unreachable the
 * {@code fallbackPrices} (keyed by symbol) keep the demo alive with clearly-labelled stale data.
 */
@ConfigurationProperties(prefix = "cryptobot.marketdata")
public record MarketDataProperties(
        String jupiterBaseUrl,
        Duration timeout,
        Map<String, String> aliases,
        Map<String, BigDecimal> fallbackPrices,
        boolean jupiterEnabled) {

    public MarketDataProperties {
        jupiterBaseUrl = jupiterBaseUrl == null || jupiterBaseUrl.isBlank() ? "https://lite-api.jup.ag" : jupiterBaseUrl.trim();
        timeout = timeout == null ? Duration.ofSeconds(6) : timeout;
        aliases = aliases == null ? Map.of() : Map.copyOf(aliases);
        fallbackPrices = fallbackPrices == null ? Map.of() : Map.copyOf(fallbackPrices);
    }
}
