package io.lifeengine.cryptobot.adapters.marketdata;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.lifeengine.cryptobot.application.backtest.BacktestProperties;
import io.lifeengine.cryptobot.trading.backtest.PriceBar;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Historical closes from Coinbase Exchange ({@code GET /products/{product}/candles?granularity&start&end}),
 * keyless (KAN-1216). One request returns at most {@value #PAGE} candles, newest first, as
 * {@code [time, low, high, open, close, volume]} with an inclusive {@code end}; this walks the window page by
 * page, in order, and returns the closes sorted ascending, de-duplicated by time, inside {@code [from, to)}.
 *
 * <p>Unlike a {@link PriceSource}, a failure here is an error, not an empty answer: a backtest over a series
 * with silent holes would measure a market that did not exist.
 */
@Component
public class CoinbaseCandleHistory {

    static final int PAGE = 300;
    /** The granularities Coinbase accepts, in seconds. */
    public static final Set<Long> GRANULARITIES = Set.of(60L, 300L, 900L, 3_600L, 21_600L, 86_400L);

    private final WebClient webClient;
    private final BacktestProperties properties;
    private final ObjectMapper objectMapper;

    public CoinbaseCandleHistory(WebClient.Builder builder, BacktestProperties properties, ObjectMapper objectMapper) {
        this.webClient = builder.baseUrl(properties.historyBaseUrl()).build();
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public Mono<List<PriceBar>> closes(String product, Duration granularity, Instant from, Instant to) {
        long g = granularity.toSeconds();
        if (!GRANULARITIES.contains(g)) {
            return Mono.error(new IllegalArgumentException("granularity not supported by Coinbase: " + g + "s"));
        }
        if (!from.isBefore(to)) {
            return Mono.error(new IllegalArgumentException("from must be before to"));
        }
        long expected = Duration.between(from, to).toSeconds() / g;
        if (expected > properties.maxBars()) {
            return Mono.error(new IllegalArgumentException(
                    "window of " + expected + " bars exceeds cryptobot.backtest.max-bars=" + properties.maxBars()));
        }
        List<Instant[]> pages = new ArrayList<>();
        for (Instant start = from; start.isBefore(to); start = start.plusSeconds(g * PAGE)) {
            Instant end = start.plusSeconds(g * (PAGE - 1));
            pages.add(new Instant[] {start, end.isAfter(to) ? to : end});
        }
        return Flux.fromIterable(pages)
                .concatMap(p -> page(product, g, p[0], p[1]))
                .collect(TreeMap<Instant, BigDecimal>::new, (acc, bars) -> bars.forEach(acc::putIfAbsent))
                .map(byTime -> {
                    List<PriceBar> out = new ArrayList<>();
                    for (Map.Entry<Instant, BigDecimal> e : byTime.entrySet()) {
                        if (!e.getKey().isBefore(from) && e.getKey().isBefore(to)) {
                            out.add(new PriceBar(e.getKey(), e.getValue()));
                        }
                    }
                    return out;
                });
    }

    private Mono<Map<Instant, BigDecimal>> page(String product, long granularity, Instant start, Instant end) {
        return webClient
                .get()
                .uri(b -> b.path("/products/{product}/candles")
                        .queryParam("granularity", granularity)
                        .queryParam("start", start.toString())
                        .queryParam("end", end.toString())
                        .build(product))
                .retrieve()
                .bodyToMono(String.class)
                .timeout(properties.timeout())
                .map(this::parse);
    }

    private Map<Instant, BigDecimal> parse(String raw) {
        Map<Instant, BigDecimal> out = new TreeMap<>();
        try {
            // floats as BigDecimal: a close is a decimal string on the wire, never a binary double here
            JsonNode root = objectMapper.reader().with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).readTree(raw);
            if (!root.isArray()) {
                throw new IllegalStateException("Coinbase candles: expected an array, got " + root.getNodeType());
            }
            for (JsonNode c : root) {
                if (c.isArray() && c.size() >= 5) {
                    out.put(Instant.ofEpochSecond(c.get(0).asLong()), c.get(4).decimalValue());
                }
            }
        } catch (IllegalStateException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("Unparseable Coinbase candles response", ex);
        }
        return out;
    }
}
