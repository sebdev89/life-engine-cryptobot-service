package io.lifeengine.cryptobot.infrastructure.binance;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

/**
 * Binance public REST only — no API keys, read-only. Used for 24h ticker snapshots.
 * Mirrors the modulith client; relocated to {@code cryptobot-service} so the runtime
 * never reaches out to Binance directly.
 *
 * <p>Phase 1 returns {@link Mono#empty()} on any upstream failure so the caller can
 * fall back to a deterministic snapshot cleanly rather than propagate a 5xx.
 */
@Component
public class BinancePublicClient {

    private static final Logger log = LoggerFactory.getLogger(BinancePublicClient.class);

    private final WebClient webClient;
    private final BinanceProperties properties;

    public BinancePublicClient(@Qualifier("binancePublicWebClient") WebClient webClient,
                               BinanceProperties properties) {
        this.webClient = webClient;
        this.properties = properties;
    }

    public Mono<BinanceTicker24h> ticker24h(String symbolUpper) {
        String sym = symbolUpper == null ? "" : symbolUpper.trim().toUpperCase();
        if (sym.isBlank()) {
            return Mono.empty();
        }
        return webClient.get()
                .uri(uri -> uri.path("/api/v3/ticker/24hr").queryParam("symbol", sym).build())
                .retrieve()
                .bodyToMono(JsonNode.class)
                .timeout(properties.timeout())
                .map(BinancePublicClient::mapTicker)
                .doOnError(e -> logFailure(sym, e))
                .onErrorResume(e -> Mono.empty());
    }

    private static void logFailure(String sym, Throwable e) {
        if (e instanceof WebClientResponseException w) {
            log.debug("binance public {} {} body={}",
                    sym, w.getStatusCode().value(), truncate(w.getResponseBodyAsString(), 512));
        } else {
            log.debug("binance public {} {}", sym, e.toString());
        }
    }

    private static BinanceTicker24h mapTicker(JsonNode n) {
        return new BinanceTicker24h(
                bd(n, "lastPrice"),
                bd(n, "priceChangePercent"),
                bd(n, "highPrice"),
                bd(n, "lowPrice"),
                bd(n, "quoteVolume"));
    }

    private static BigDecimal bd(JsonNode n, String field) {
        if (n == null || !n.has(field)) {
            return BigDecimal.ZERO;
        }
        try {
            return new BigDecimal(n.get(field).asText("0"));
        } catch (Exception e) {
            return BigDecimal.ZERO;
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    public record BinanceTicker24h(
            BigDecimal lastPrice,
            BigDecimal change24hPct,
            BigDecimal high24h,
            BigDecimal low24h,
            BigDecimal quoteVolume24h) {

        public BinanceTicker24h {
            if (lastPrice == null) lastPrice = BigDecimal.ZERO;
            if (change24hPct == null) change24hPct = BigDecimal.ZERO;
            if (high24h == null) high24h = BigDecimal.ZERO;
            if (low24h == null) low24h = BigDecimal.ZERO;
            if (quoteVolume24h == null) quoteVolume24h = BigDecimal.ZERO;
        }

        public BigDecimal changePctScaled() {
            return change24hPct.setScale(4, RoundingMode.HALF_UP);
        }
    }
}
