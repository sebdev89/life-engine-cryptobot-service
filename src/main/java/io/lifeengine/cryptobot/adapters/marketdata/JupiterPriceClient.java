package io.lifeengine.cryptobot.adapters.marketdata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.lifeengine.cryptobot.core.oracle.PriceObservation;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * Jupiter Price API v3 ({@code GET /price/v3?ids=<mint,...>}), keyless — a DEX-aggregator view of
 * the price. One of the oracle's independent sources; it no longer falls back to static
 * prices itself: what it cannot price it does not observe, and the oracle counts that against the
 * quorum. Jupiter publishes no timestamp, so an observation is dated at fetch time.
 */
@Component
public class JupiterPriceClient implements PriceSource {

    private static final Logger log = LoggerFactory.getLogger(JupiterPriceClient.class);
    public static final String SOURCE_JUPITER = "jupiter-price-v3";

    private final WebClient webClient;
    private final MarketDataProperties properties;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Autowired
    public JupiterPriceClient(WebClient.Builder builder, MarketDataProperties properties, ObjectMapper objectMapper) {
        this(builder, properties, objectMapper, Clock.systemUTC());
    }

    public JupiterPriceClient(WebClient.Builder builder, MarketDataProperties properties, ObjectMapper objectMapper, Clock clock) {
        this.webClient = builder.baseUrl(properties.jupiterBaseUrl()).build();
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Override
    public String id() {
        return SOURCE_JUPITER;
    }

    @Override
    public boolean enabled() {
        return properties.jupiterEnabled();
    }

    @Override
    public Mono<List<PriceObservation>> observe(Map<String, String> symbolByMint) {
        if (symbolByMint == null || symbolByMint.isEmpty() || !enabled()) {
            return Mono.just(List.of());
        }
        String ids = String.join(",", symbolByMint.keySet());
        return webClient
                .get()
                .uri(b -> b.path("/price/v3").queryParam("ids", ids).build())
                .retrieve()
                .bodyToMono(String.class)
                .timeout(properties.timeout())
                .map(raw -> parse(raw, symbolByMint))
                .onErrorResume(ex -> {
                    log.warn("jupiter_price_failed ids={} error={}", ids, ex.toString());
                    return Mono.just(List.of());
                });
    }

    private List<PriceObservation> parse(String raw, Map<String, String> symbolByMint) {
        List<PriceObservation> out = new ArrayList<>();
        try {
            JsonNode root = objectMapper.readTree(raw);
            // v3 shape: { "<mint>": { "usdPrice": 102.9, "priceChange24h": 1.9, ... } }
            // v2 shape (older lite endpoints): { "data": { "<mint>": { "price": "102.9" } } }
            JsonNode container = root.has("data") ? root.get("data") : root;
            Instant now = clock.instant();
            for (Iterator<Map.Entry<String, JsonNode>> it = container.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> e = it.next();
                String symbol = symbolByMint.get(e.getKey());
                JsonNode v = e.getValue();
                if (symbol == null || v == null || v.isNull()) {
                    continue;
                }
                JsonNode price = v.has("usdPrice") ? v.get("usdPrice") : v.get("price");
                if (price == null || price.isNull()) {
                    continue;
                }
                out.add(new PriceObservation(SOURCE_JUPITER, symbol, e.getKey(), new BigDecimal(price.asText()), now));
            }
        } catch (Exception ex) {
            throw new IllegalStateException("Unparseable Jupiter price response", ex);
        }
        return out;
    }
}
