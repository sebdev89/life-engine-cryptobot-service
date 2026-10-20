package io.lifeengine.cryptobot.application.backtest;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Backtest configuration (KAN-1216, CryptoBot v2). History comes from Coinbase Exchange's public candles
 * ({@code GET /products/{product}/candles}), keyless: as of 2026-10-20 Pyth Benchmarks answers 404, Hermes 401
 * without a key, and Jupiter has no price history. {@code maxBars} caps one request (and the work behind it).
 */
@ConfigurationProperties(prefix = "cryptobot.backtest")
public record BacktestProperties(String historyBaseUrl, String product, Duration timeout, Integer maxBars) {

    public BacktestProperties {
        historyBaseUrl = historyBaseUrl == null || historyBaseUrl.isBlank() ? "https://api.exchange.coinbase.com" : historyBaseUrl.trim();
        product = product == null || product.isBlank() ? "SOL-USD" : product.trim();
        timeout = timeout == null ? Duration.ofSeconds(10) : timeout;
        maxBars = maxBars == null ? 5_000 : maxBars;
    }
}
