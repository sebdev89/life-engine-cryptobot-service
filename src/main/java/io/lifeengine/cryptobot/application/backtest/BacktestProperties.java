package io.lifeengine.cryptobot.application.backtest;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/**
 * Backtest configuration (KAN-1216, CryptoBot v2). History comes from Coinbase Exchange's public candles
 * ({@code GET /products/{product}/candles}), keyless: as of 2026-10-20 Pyth Benchmarks answers 404, Hermes 401
 * without a key, and Jupiter has no price history. {@code maxBars} caps one request (and the work behind it).
 *
 * <p>{@code stocks} (KAN-1226): daily or hourly stock history from Yahoo's chart API, keyless and NOT an official
 * API — it can change or stop; a failure is an error, never a partial series. It answers 429 to a request with
 * no User-Agent, so the client sends an identifiable one rather than impersonating a browser.
 */
@ConfigurationProperties(prefix = "cryptobot.backtest")
public record BacktestProperties(String historyBaseUrl, String product, Duration timeout, Integer maxBars, Stocks stocks) {

    @ConstructorBinding
    public BacktestProperties {
        historyBaseUrl = historyBaseUrl == null || historyBaseUrl.isBlank() ? "https://api.exchange.coinbase.com" : historyBaseUrl.trim();
        product = product == null || product.isBlank() ? "SOL-USD" : product.trim();
        timeout = timeout == null ? Duration.ofSeconds(10) : timeout;
        maxBars = maxBars == null ? 5_000 : maxBars;
        stocks = stocks == null ? new Stocks(null, null, null) : stocks;
    }

    /** The crypto-only shape, kept for the tests and callers that predate stock backtests. */
    public BacktestProperties(String historyBaseUrl, String product, Duration timeout, Integer maxBars) {
        this(historyBaseUrl, product, timeout, maxBars, null);
    }

    /** Stock history source and the index a stock is compared with when the request names none. */
    public record Stocks(String baseUrl, String userAgent, String defaultBenchmark) {
        public Stocks {
            baseUrl = baseUrl == null || baseUrl.isBlank() ? "https://query1.finance.yahoo.com" : baseUrl.trim();
            userAgent = userAgent == null || userAgent.isBlank() ? "life-engine-cryptobot/1.0" : userAgent.trim();
            defaultBenchmark = defaultBenchmark == null || defaultBenchmark.isBlank() ? "QQQ" : defaultBenchmark.trim();
        }
    }
}
