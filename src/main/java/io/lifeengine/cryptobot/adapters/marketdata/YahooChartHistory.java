package io.lifeengine.cryptobot.adapters.marketdata;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.lifeengine.cryptobot.application.backtest.BacktestProperties;
import io.lifeengine.cryptobot.trading.backtest.PriceBar;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.springframework.http.HttpHeaders;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * Historical stock closes from Yahoo's chart API ({@code GET /v8/finance/chart/{symbol}?period1&period2&interval}),
 * keyless (KAN-1226). Daily ({@code 1d}) or hourly ({@code 60m}) bars, sorted ascending, inside {@code [from, to)}.
 * Bars without a close (Yahoo returns {@code null} for halted or still-forming intervals) are left out, never
 * filled: a backtest over invented prices would measure a market that did not exist.
 *
 * <p>Not an official API: a failure (HTTP error, {@code chart.error}, an unparseable body) is an error, never an
 * empty or partial series. A daily bar is stamped at the session open (13:30 UTC for US stocks) and closes at
 * the session close; the backtester only uses its close.
 *
 * <p><b>Usage limits (KAN-1226, LEAD):</b> Yahoo's terms restrict automated use, so this client serves only
 * backtests a person asks for through {@code POST /backtests}: never a cron, a job or a loop. Each series is
 * cached per UTC day and request (symbol, interval, window), so asking the same backtest again the same day does
 * not call Yahoo again; failures are not cached. If stock data ever becomes recurring or part of a product, it
 * needs a licensed source (a spend, so a human gate). Stock backtests enable nothing: no broker, no capital, and
 * they are not part of the paper-trading window (KAN-1217).
 */
@Component
public class YahooChartHistory {

    /** The granularities this client maps to Yahoo intervals, in seconds. */
    public static final Map<Long, String> INTERVALS = Map.of(86_400L, "1d", 3_600L, "60m");
    /** US tickers, index and class shares: SPCX, QQQ, BRK.B, ^GSPC. */
    public static final Pattern SYMBOL = Pattern.compile("^\\^?[A-Z][A-Z0-9.\\-]{0,9}$");

    /** Distinct requests kept per day; past it the day's cache starts over (a backtest is asked by a person). */
    static final int MAX_CACHED = 256;

    private final WebClient webClient;
    private final BacktestProperties properties;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final Map<String, Mono<List<PriceBar>>> cache = new ConcurrentHashMap<>();

    @Autowired
    public YahooChartHistory(WebClient.Builder builder, BacktestProperties properties, ObjectMapper objectMapper) {
        this(builder, properties, objectMapper, Clock.systemUTC());
    }

    public YahooChartHistory(WebClient.Builder builder, BacktestProperties properties, ObjectMapper objectMapper, Clock clock) {
        this.webClient = builder.baseUrl(properties.stocks().baseUrl())
                .defaultHeader(HttpHeaders.USER_AGENT, properties.stocks().userAgent())
                .build();
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public Mono<List<PriceBar>> closes(String symbol, Duration granularity, Instant from, Instant to) {
        String interval = INTERVALS.get(granularity.toSeconds());
        if (interval == null) {
            return Mono.error(new IllegalArgumentException("stock granularity must be 86400 (1d) or 3600 (1h), not " + granularity.toSeconds()));
        }
        if (symbol == null || !SYMBOL.matcher(symbol).matches()) {
            return Mono.error(new IllegalArgumentException("not a stock symbol: " + symbol));
        }
        if (!from.isBefore(to)) {
            return Mono.error(new IllegalArgumentException("from must be before to"));
        }
        long calendarBars = Duration.between(from, to).toSeconds() / granularity.toSeconds();
        if (calendarBars > properties.maxBars()) {
            return Mono.error(new IllegalArgumentException(
                    "window of " + calendarBars + " bars exceeds cryptobot.backtest.max-bars=" + properties.maxBars()));
        }
        String today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC).toString();
        cache.keySet().removeIf(k -> !k.startsWith(today));
        if (cache.size() >= MAX_CACHED) {
            cache.clear();
        }
        String key = today + "|" + symbol + "|" + interval + "|" + from.getEpochSecond() + "|" + to.getEpochSecond();
        Mono<List<PriceBar>> series = cache.computeIfAbsent(key, k -> fetch(symbol, interval, from, to)
                // a series is kept for the day; an error or an empty answer is not, so the next ask retries
                .cache(bars -> Duration.ofDays(1), error -> Duration.ZERO, () -> Duration.ZERO));
        return series.doOnError(ex -> cache.remove(key));
    }

    private Mono<List<PriceBar>> fetch(String symbol, String interval, Instant from, Instant to) {
        return webClient
                .get()
                .uri(b -> b.path("/v8/finance/chart/{symbol}")
                        .queryParam("period1", from.getEpochSecond())
                        .queryParam("period2", to.getEpochSecond())
                        .queryParam("interval", interval)
                        .build(symbol))
                .retrieve()
                .bodyToMono(String.class)
                .timeout(properties.timeout())
                .map(raw -> parse(symbol, raw, from, to));
    }

    private List<PriceBar> parse(String symbol, String raw, Instant from, Instant to) {
        JsonNode root;
        try {
            // floats as BigDecimal: never a binary double in a price
            root = objectMapper.reader().with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).readTree(raw);
        } catch (Exception ex) {
            throw new IllegalStateException("Unparseable Yahoo chart response for " + symbol, ex);
        }
        JsonNode chart = root.path("chart");
        if (!chart.path("error").isMissingNode() && !chart.path("error").isNull()) {
            throw new IllegalStateException("Yahoo chart error for " + symbol + ": " + chart.path("error").path("description").asText("unknown"));
        }
        JsonNode result = chart.path("result").path(0);
        JsonNode times = result.path("timestamp");
        JsonNode closes = result.path("indicators").path("quote").path(0).path("close");
        if (!times.isArray() || !closes.isArray() || times.size() != closes.size()) {
            throw new IllegalStateException("Yahoo chart for " + symbol + ": timestamps and closes do not line up");
        }
        TreeMap<Instant, PriceBar> byTime = new TreeMap<>();
        for (int i = 0; i < times.size(); i++) {
            JsonNode c = closes.get(i);
            if (c == null || c.isNull()) {
                continue;
            }
            Instant t = Instant.ofEpochSecond(times.get(i).asLong());
            if (!t.isBefore(from) && t.isBefore(to)) {
                byTime.putIfAbsent(t, new PriceBar(t, c.decimalValue()));
            }
        }
        return new ArrayList<>(byTime.values());
    }
}
