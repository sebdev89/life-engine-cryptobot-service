package io.lifeengine.cryptobot.infrastructure.solana;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

/**
 * Solana public market data, keyless and read-only — the twin of {@code BinancePublicClient}.
 *
 * <ul>
 *   <li>GeckoTerminal {@code /networks/solana/pools/{pool}} → price, 24h change, 24h volume of a DEX pool.
 *   <li>GeckoTerminal {@code /pools/{pool}/ohlcv/hour} → hourly candles.
 *   <li>Jupiter Price v3 {@code /price/v3?ids=<mint>} → spot price of a token by mint.
 * </ul>
 *
 * <p>Like the Binance client, every method returns {@link Mono#empty()} on failure so the
 * provider can fall back instead of surfacing a 5xx.
 */
@Component
public class SolanaPublicClient {

    private static final Logger log = LoggerFactory.getLogger(SolanaPublicClient.class);

    private final WebClient gecko;
    private final WebClient jupiter;
    private final SolanaMarketProperties properties;

    public SolanaPublicClient(WebClient.Builder builder, SolanaMarketProperties properties) {
        this.gecko = builder.clone().baseUrl(properties.geckoTerminalBaseUrl()).defaultHeader("Accept", "application/json").build();
        this.jupiter = builder.clone().baseUrl(properties.jupiterBaseUrl()).defaultHeader("Accept", "application/json").build();
        this.properties = properties;
    }

    public record PoolStats(
            String poolAddress,
            String name,
            String baseSymbol,
            String quoteSymbol,
            String baseMint,
            BigDecimal priceUsd,
            BigDecimal change24hPct,
            BigDecimal volume24hUsd,
            long trades24h) {}

    public record Candle(Instant openTime, BigDecimal open, BigDecimal high, BigDecimal low, BigDecimal close, BigDecimal volumeUsd) {}

    public record TokenPrice(String mint, BigDecimal priceUsd, BigDecimal change24hPct, Instant asOf) {}

    public Mono<PoolStats> poolStats(String poolAddress) {
        if (poolAddress == null || poolAddress.isBlank()) {
            return Mono.empty();
        }
        return gecko.get()
                .uri("/api/v2/networks/solana/pools/{pool}", poolAddress)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .timeout(properties.timeout())
                .map(n -> mapPool(poolAddress, n))
                .doOnError(e -> logFailure("pool " + poolAddress, e))
                .onErrorResume(e -> Mono.empty());
    }

    public Mono<List<Candle>> ohlcvHour(String poolAddress, int limit) {
        int capped = Math.max(1, Math.min(limit, 1000));
        return gecko.get()
                .uri(b -> b.path("/api/v2/networks/solana/pools/{pool}/ohlcv/hour").queryParam("limit", capped).build(poolAddress))
                .retrieve()
                .bodyToMono(JsonNode.class)
                .timeout(properties.timeout())
                .map(SolanaPublicClient::mapCandles)
                .doOnError(e -> logFailure("ohlcv " + poolAddress, e))
                .onErrorResume(e -> Mono.empty());
    }

    public Mono<TokenPrice> jupiterPrice(String mint) {
        if (mint == null || mint.isBlank()) {
            return Mono.empty();
        }
        return jupiter.get()
                .uri(b -> b.path("/price/v3").queryParam("ids", mint).build())
                .retrieve()
                .bodyToMono(JsonNode.class)
                .timeout(properties.timeout())
                .flatMap(n -> {
                    JsonNode entry = n.has("data") ? n.get("data").get(mint) : n.get(mint);
                    if (entry == null || entry.isNull()) {
                        return Mono.empty();
                    }
                    JsonNode price = entry.has("usdPrice") ? entry.get("usdPrice") : entry.get("price");
                    if (price == null || price.isNull()) {
                        return Mono.empty();
                    }
                    return Mono.just(new TokenPrice(mint, new BigDecimal(price.asText()), bd(entry, "priceChange24h"), Instant.now()));
                })
                .doOnError(e -> logFailure("jupiter " + mint, e))
                .onErrorResume(e -> Mono.empty());
    }

    private static PoolStats mapPool(String poolAddress, JsonNode root) {
        JsonNode a = root.path("data").path("attributes");
        String name = a.path("name").asText("");
        String[] parts = name.split("/");
        String base = parts.length > 0 ? parts[0].trim() : "";
        String quote = parts.length > 1 ? parts[1].trim() : "";
        String baseMint = root.path("data").path("relationships").path("base_token").path("data").path("id").asText("");
        if (baseMint.startsWith("solana_")) {
            baseMint = baseMint.substring("solana_".length());
        }
        JsonNode tx = a.path("transactions").path("h24");
        long trades = tx.path("buys").asLong(0) + tx.path("sells").asLong(0);
        return new PoolStats(
                poolAddress,
                name,
                base,
                quote,
                baseMint,
                bd(a, "base_token_price_usd"),
                bd(a.path("price_change_percentage"), "h24"),
                bd(a.path("volume_usd"), "h24"),
                trades);
    }

    private static List<Candle> mapCandles(JsonNode root) {
        List<Candle> out = new ArrayList<>();
        for (JsonNode row : root.path("data").path("attributes").path("ohlcv_list")) {
            if (row.size() < 6) {
                continue;
            }
            out.add(new Candle(
                    Instant.ofEpochSecond(row.get(0).asLong()),
                    row.get(1).decimalValue(),
                    row.get(2).decimalValue(),
                    row.get(3).decimalValue(),
                    row.get(4).decimalValue(),
                    row.get(5).decimalValue()));
        }
        return out;
    }

    private static BigDecimal bd(JsonNode n, String field) {
        if (n == null || !n.has(field) || n.get(field).isNull()) {
            return BigDecimal.ZERO;
        }
        try {
            return new BigDecimal(n.get(field).asText("0"));
        } catch (Exception e) {
            return BigDecimal.ZERO;
        }
    }

    private static void logFailure(String what, Throwable e) {
        if (e instanceof WebClientResponseException w) {
            log.debug("solana public {} {} body={}", what, w.getStatusCode().value(), w.getResponseBodyAsString());
        } else {
            log.debug("solana public {} {}", what, e.toString());
        }
    }
}
