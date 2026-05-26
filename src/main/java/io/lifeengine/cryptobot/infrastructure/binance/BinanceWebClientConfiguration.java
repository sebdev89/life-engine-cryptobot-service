package io.lifeengine.cryptobot.infrastructure.binance;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
@EnableConfigurationProperties(BinanceProperties.class)
public class BinanceWebClientConfiguration {

    @Bean("binancePublicWebClient")
    WebClient binancePublicWebClient(BinanceProperties properties) {
        return WebClient.builder()
                .baseUrl(properties.baseUrl())
                .defaultHeader("Accept", "application/json")
                .build();
    }
}
