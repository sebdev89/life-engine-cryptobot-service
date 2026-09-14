package io.lifeengine.cryptobot.adapters.marketdata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * Jupiter Price API v3 ({@code GET /price/v3?ids=<mint,...>}), keyless. Primary oracle. On any
 * failure it falls back to {@link MarketDataProperties#fallbackPrices()} so the demo degrades to
 * labelled stale prices instead of an error page.
 */
@Component
public class JupiterPriceClient implements PriceProvider {

    private static final Logger log = LoggerFactory.getLogger(JupiterPriceClient.class);
    public static final String SOURCE_JUPITER = "jupiter-price-v3";
    public static final String SOURCE_FALLBACK = "fallback-static";

    private final WebClient webClient;
    private final MarketDataProperties properties;
    private final TokenRegistry registry;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public JupiterPriceClient(
            WebClient.Builder builder, MarketDataProperties properties, TokenRegistry registry, ObjectMapper objectMapper) {
        this.webClient = builder.baseUrl(properties.jupiterBaseUrl()).build();
        this.properties = properties;
        this.registry = registry;
        this.objectMapper = objectMapper;
        this.clock = Clock.systemUTC();
    }

    @Override
    public Mono<Map<String, PriceQuote>> prices(Set<String> mainnetMints) {
        if (mainnetMints == null || mainnetMints.isEmpty()) {
            return Mono.just(Map.of());
        }
        if (!properties.jupiterEnabled()) {
            return Mono.just(fallback(mainnetMints));
        }
        String ids = String.join(",", mainnetMints);
        return webClient
                .get()
                .uri(b -> b.path("/price/v3").queryParam("ids", ids).build())
                .retrieve()
                .bodyToMono(String.class)
                .timeout(properties.timeout())
                .map(this::parse)
                .map(
                        quotes -> {
                            // Anything Jupiter did not price gets the static fallback (if configured).
                            Map<String, PriceQuote> merged = new LinkedHashMap<>(quotes);
                            fallback(mainnetMints).forEach(merged::putIfAbsent);
                            return merged;
                        })
                .onErrorResume(
                        ex -> {
                            log.warn("jupiter_price_failed ids={} error={}", ids, ex.toString());
                            return Mono.just(fallback(mainnetMints));
                        });
    }

    private Map<String, PriceQuote> parse(String raw) {
        Map<String, PriceQuote> out = new LinkedHashMap<>();
        try {
            JsonNode root = objectMapper.readTree(raw);
            // v3 shape: { "<mint>": { "usdPrice": 102.9, "priceChange24h": 1.9, ... } }
            // v2 shape (older lite endpoints): { "data": { "<mint>": { "price": "102.9" } } }
            JsonNode container = root.has("data") ? root.get("data") : root;
            Instant now = clock.instant();
            for (Iterator<Map.Entry<String, JsonNode>> it = container.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> e = it.next();
                JsonNode v = e.getValue();
                if (v == null || v.isNull()) {
                    continue;
                }
                JsonNode price = v.has("usdPrice") ? v.get("usdPrice") : v.get("price");
                if (price == null || price.isNull()) {
                    continue;
                }
                BigDecimal change = v.has("priceChange24h") && v.get("priceChange24h").isNumber()
                        ? v.get("priceChange24h").decimalValue()
                        : null;
                out.put(e.getKey(), new PriceQuote(e.getKey(), new BigDecimal(price.asText()), SOURCE_JUPITER, now, change));
            }
        } catch (Exception ex) {
            throw new IllegalStateException("Unparseable Jupiter price response", ex);
        }
        return out;
    }

    private Map<String, PriceQuote> fallback(Set<String> mainnetMints) {
        Map<String, PriceQuote> out = new LinkedHashMap<>();
        Instant now = clock.instant();
        for (String mint : mainnetMints) {
            String symbol = registry.symbolOf(mint);
            BigDecimal price = properties.fallbackPrices().get(symbol);
            if (price == null) {
                price = properties.fallbackPrices().get(symbol.toUpperCase());
            }
            if (price != null) {
                out.put(mint, new PriceQuote(mint, price, SOURCE_FALLBACK, now, null));
            }
        }
        return out;
    }
}
