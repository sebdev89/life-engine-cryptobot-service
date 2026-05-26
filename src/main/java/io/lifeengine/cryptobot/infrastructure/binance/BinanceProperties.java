package io.lifeengine.cryptobot.infrastructure.binance;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "cryptobot.binance")
public record BinanceProperties(String baseUrl, Duration timeout) {

    public BinanceProperties {
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = "https://api.binance.com";
        }
        if (timeout == null) {
            timeout = Duration.ofSeconds(3);
        }
    }
}
