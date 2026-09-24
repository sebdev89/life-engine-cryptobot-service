package io.lifeengine.cryptobot.adapters.marketdata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.lifeengine.cryptobot.domain.oracle.PriceObservation;
import java.math.BigDecimal;
import java.time.Clock;
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
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Coinbase spot price ({@code GET /v2/prices/{product}/spot}), keyless — a single exchange's
 * order book, the third mechanism next to the DEX aggregator (Jupiter) and the CEX aggregator
 * (CoinGecko). KAN-572: added because Pyth Hermes now answers {@code 401} without an API key and
 * CoinGecko's public tier lags the market by minutes, which left the oracle one source short of
 * its quorum on a real run. One request per asset, concurrently; dated by fetch time (Coinbase
 * returns no timestamp). Products come from {@code cryptobot.marketdata.coinbase.products}
 * (symbol → {@code SOL-USD}).
 */
@Component
public class CoinbaseSpotPriceSource implements PriceSource {

    private static final Logger log = LoggerFactory.getLogger(CoinbaseSpotPriceSource.class);
    public static final String SOURCE_COINBASE = "coinbase-spot";

    private final WebClient webClient;
    private final MarketDataProperties properties;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Autowired
    public CoinbaseSpotPriceSource(WebClient.Builder builder, MarketDataProperties properties, ObjectMapper objectMapper) {
        this(builder, properties, objectMapper, Clock.systemUTC());
    }

    public CoinbaseSpotPriceSource(WebClient.Builder builder, MarketDataProperties properties, ObjectMapper objectMapper, Clock clock) {
        this.webClient = builder.baseUrl(properties.coinbase().baseUrl()).build();
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Override
    public String id() {
        return SOURCE_COINBASE;
    }

    @Override
    public boolean enabled() {
        return properties.coinbase().enabled();
    }

    @Override
    public Mono<List<PriceObservation>> observe(Map<String, String> symbolByMint) {
        if (symbolByMint == null || symbolByMint.isEmpty() || !enabled()) {
            return Mono.just(List.of());
        }
        Map<String, Map.Entry<String, String>> byProduct = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : symbolByMint.entrySet()) {
            String product = productOf(e.getValue());
            if (product != null) {
                byProduct.put(product, Map.entry(e.getValue(), e.getKey()));
            }
        }
        if (byProduct.isEmpty()) {
            return Mono.just(List.of());
        }
        return Flux.fromIterable(byProduct.entrySet())
                .flatMap(e -> one(e.getKey(), e.getValue().getKey(), e.getValue().getValue()))
                .collectList()
                .map(list -> {
                    List<PriceObservation> out = new ArrayList<>();
                    list.forEach(out::addAll);
                    return out;
                });
    }

    private Mono<List<PriceObservation>> one(String product, String symbol, String mint) {
        return webClient
                .get()
                .uri("/v2/prices/{product}/spot", product)
                .retrieve()
                .bodyToMono(String.class)
                .timeout(properties.timeout())
                .map(raw -> parse(raw, symbol, mint))
                .onErrorResume(ex -> {
                    log.warn("coinbase_price_failed product={} error={}", product, ex.toString());
                    return Mono.just(List.of());
                });
    }

    private String productOf(String symbol) {
        Map<String, String> products = properties.coinbase().products();
        String p = products.get(symbol);
        if (p == null) {
            p = products.get(symbol.toUpperCase(Locale.ROOT));
        }
        if (p == null) {
            p = products.get(symbol.toLowerCase(Locale.ROOT));
        }
        return p == null || p.isBlank() ? null : p.trim();
    }

    private List<PriceObservation> parse(String raw, String symbol, String mint) {
        try {
            JsonNode data = objectMapper.readTree(raw).path("data");
            if (!data.hasNonNull("amount")) {
                return List.of();
            }
            return List.of(new PriceObservation(SOURCE_COINBASE, symbol, mint, new BigDecimal(data.get("amount").asText()), clock.instant()));
        } catch (Exception ex) {
            throw new IllegalStateException("Unparseable Coinbase response", ex);
        }
    }
}
