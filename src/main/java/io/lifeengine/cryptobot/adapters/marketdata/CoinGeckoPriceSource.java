package io.lifeengine.cryptobot.adapters.marketdata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.lifeengine.cryptobot.core.oracle.PriceObservation;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * CoinGecko simple price ({@code GET /api/v3/simple/price?ids=…&vs_currencies=usd}), keyless on
 * the public tier — the CEX-aggregator view of the price. Coin ids come from
 * {@code cryptobot.marketdata.coingecko.ids} (symbol → id). Dated by CoinGecko's own
 * {@code last_updated_at} when present (it lags the market by up to a couple of minutes, which is
 * exactly what the freshness bound is for), by fetch time otherwise.
 */
@Component
public class CoinGeckoPriceSource implements PriceSource {

    private static final Logger log = LoggerFactory.getLogger(CoinGeckoPriceSource.class);
    public static final String SOURCE_COINGECKO = "coingecko-simple";

    private final WebClient webClient;
    private final MarketDataProperties properties;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Autowired
    public CoinGeckoPriceSource(WebClient.Builder builder, MarketDataProperties properties, ObjectMapper objectMapper) {
        this(builder, properties, objectMapper, Clock.systemUTC());
    }

    public CoinGeckoPriceSource(WebClient.Builder builder, MarketDataProperties properties, ObjectMapper objectMapper, Clock clock) {
        this.webClient = builder.baseUrl(properties.coingecko().baseUrl()).build();
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Override
    public String id() {
        return SOURCE_COINGECKO;
    }

    @Override
    public boolean enabled() {
        return properties.coingecko().enabled();
    }

    @Override
    public Mono<List<PriceObservation>> observe(Map<String, String> symbolByMint) {
        if (symbolByMint == null || symbolByMint.isEmpty() || !enabled()) {
            return Mono.just(List.of());
        }
        // coin id → (symbol, mint)
        Map<String, Map.Entry<String, String>> byCoin = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : symbolByMint.entrySet()) {
            String coin = coinOf(e.getValue());
            if (coin != null) {
                byCoin.put(coin, Map.entry(e.getValue(), e.getKey()));
            }
        }
        if (byCoin.isEmpty()) {
            return Mono.just(List.of());
        }
        String ids = String.join(",", byCoin.keySet());
        return webClient
                .get()
                .uri(b -> b.path("/api/v3/simple/price").queryParam("ids", ids).queryParam("vs_currencies", "usd")
                        .queryParam("include_last_updated_at", "true").build())
                .retrieve()
                .bodyToMono(String.class)
                .timeout(properties.timeout())
                .map(raw -> parse(raw, byCoin))
                .onErrorResume(ex -> {
                    log.warn("coingecko_price_failed ids={} error={}", ids, ex.toString());
                    return Mono.just(List.of());
                });
    }

    private String coinOf(String symbol) {
        Map<String, String> ids = properties.coingecko().ids();
        String id = ids.get(symbol);
        if (id == null) {
            id = ids.get(symbol.toUpperCase(Locale.ROOT));
        }
        if (id == null) {
            id = ids.get(symbol.toLowerCase(Locale.ROOT));
        }
        return id == null || id.isBlank() ? null : id.trim();
    }

    private List<PriceObservation> parse(String raw, Map<String, Map.Entry<String, String>> byCoin) {
        List<PriceObservation> out = new ArrayList<>();
        try {
            JsonNode root = objectMapper.readTree(raw);
            Instant now = clock.instant();
            for (Map.Entry<String, Map.Entry<String, String>> e : byCoin.entrySet()) {
                JsonNode coin = root.path(e.getKey());
                if (!coin.hasNonNull("usd")) {
                    continue;
                }
                Instant at = coin.hasNonNull("last_updated_at") ? Instant.ofEpochSecond(coin.get("last_updated_at").asLong()) : now;
                out.add(new PriceObservation(SOURCE_COINGECKO, e.getValue().getKey(), e.getValue().getValue(), new BigDecimal(coin.get("usd").asText()), at));
            }
        } catch (Exception ex) {
            throw new IllegalStateException("Unparseable CoinGecko response", ex);
        }
        return out;
    }
}
