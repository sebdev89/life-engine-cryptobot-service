package io.lifeengine.cryptobot.adapters.marketdata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.lifeengine.cryptobot.core.oracle.PriceObservation;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriBuilder;
import reactor.core.publisher.Mono;

/**
 * Pyth Network via Hermes ({@code GET /v2/updates/price/latest?ids[]=…&parsed=true}), keyless —
 * the pull-oracle view of the price, aggregated from first-party publishers. The only source
 * with its own {@code publish_time}, so its freshness is the network's, not our fetch's.
 * Feed ids come from {@code cryptobot.marketdata.pyth.feeds} (symbol → hex id).
 */
@Component
public class PythHermesPriceSource implements PriceSource {

    private static final Logger log = LoggerFactory.getLogger(PythHermesPriceSource.class);
    public static final String SOURCE_PYTH = "pyth-hermes";

    private final WebClient webClient;
    private final MarketDataProperties properties;
    private final ObjectMapper objectMapper;

    public PythHermesPriceSource(WebClient.Builder builder, MarketDataProperties properties, ObjectMapper objectMapper) {
        this.webClient = builder.baseUrl(properties.pyth().baseUrl()).build();
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public String id() {
        return SOURCE_PYTH;
    }

    @Override
    public boolean enabled() {
        return properties.pyth().enabled();
    }

    @Override
    public Mono<List<PriceObservation>> observe(Map<String, String> symbolByMint) {
        if (symbolByMint == null || symbolByMint.isEmpty() || !enabled()) {
            return Mono.just(List.of());
        }
        // feed id (lower-case, no 0x) → (symbol, mint)
        Map<String, Map.Entry<String, String>> byFeed = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : symbolByMint.entrySet()) {
            String feed = feedOf(e.getValue());
            if (feed != null) {
                byFeed.put(feed, Map.entry(e.getValue(), e.getKey()));
            }
        }
        if (byFeed.isEmpty()) {
            return Mono.just(List.of());
        }
        return webClient
                .get()
                .uri(b -> {
                    UriBuilder u = b.path("/v2/updates/price/latest").queryParam("parsed", "true");
                    byFeed.keySet().forEach(id -> u.queryParam("ids[]", id));
                    return u.build();
                })
                .retrieve()
                .bodyToMono(String.class)
                .timeout(properties.timeout())
                .map(raw -> parse(raw, byFeed))
                .onErrorResume(ex -> {
                    log.warn("pyth_price_failed feeds={} error={}", byFeed.keySet(), ex.toString());
                    return Mono.just(List.of());
                });
    }

    private String feedOf(String symbol) {
        String raw = properties.pyth().feeds().get(symbol);
        if (raw == null) {
            raw = properties.pyth().feeds().get(symbol.toUpperCase(Locale.ROOT));
        }
        if (raw == null) {
            raw = properties.pyth().feeds().get(symbol.toLowerCase(Locale.ROOT));
        }
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String id = raw.trim().toLowerCase(Locale.ROOT);
        return id.startsWith("0x") ? id.substring(2) : id;
    }

    private List<PriceObservation> parse(String raw, Map<String, Map.Entry<String, String>> byFeed) {
        List<PriceObservation> out = new ArrayList<>();
        try {
            JsonNode root = objectMapper.readTree(raw);
            for (JsonNode item : root.path("parsed")) {
                String id = item.path("id").asText("").toLowerCase(Locale.ROOT);
                Map.Entry<String, String> target = byFeed.get(id.startsWith("0x") ? id.substring(2) : id);
                JsonNode price = item.path("price");
                if (target == null || price.isMissingNode() || !price.hasNonNull("price") || !price.hasNonNull("expo")) {
                    continue;
                }
                BigDecimal mantissa = new BigDecimal(price.get("price").asText());
                int expo = price.get("expo").asInt();
                BigDecimal usd = mantissa.scaleByPowerOfTen(expo);
                Instant publishedAt = price.hasNonNull("publish_time") ? Instant.ofEpochSecond(price.get("publish_time").asLong()) : null;
                out.add(new PriceObservation(SOURCE_PYTH, target.getKey(), target.getValue(), usd, publishedAt));
            }
        } catch (Exception ex) {
            throw new IllegalStateException("Unparseable Hermes response", ex);
        }
        return out;
    }
}
