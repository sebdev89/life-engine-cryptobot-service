package io.lifeengine.cryptobot.api;

import io.lifeengine.cryptobot.application.MarketReviewRunService;
import io.lifeengine.cryptobot.domain.MarketReviewRun;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Read-only query surface for the local cryptobot review history.
 *
 * <ul>
 *   <li>{@code GET /api/cryptobot/market-reviews/latest?symbols=BTCUSDT,SOLUSDT}
 *       returns the latest review per symbol.
 *   <li>{@code GET /api/cryptobot/market-reviews?symbol=BTCUSDT&limit=20}
 *       returns recent reviews for one symbol, ordered by {@code started_at DESC}.
 * </ul>
 */
@RestController
@RequestMapping("/api/cryptobot/market-reviews")
public class MarketReviewsQueryController {

    private final MarketReviewRunService service;

    public MarketReviewsQueryController(MarketReviewRunService service) {
        this.service = service;
    }

    @GetMapping(path = "/latest", produces = "application/json")
    public Flux<MarketReviewSummaryResponse> latest(@RequestParam("symbols") String symbolsCsv) {
        List<String> symbols = parseSymbols(symbolsCsv);
        if (symbols.isEmpty()) {
            return Flux.empty();
        }
        return Flux.fromIterable(symbols)
                .concatMap(
                        symbol ->
                                service.findLatestBySymbol(symbol)
                                        .map(MarketReviewSummaryResponse::from)
                                        .switchIfEmpty(Mono.empty()));
    }

    @GetMapping(produces = "application/json")
    public Flux<MarketReviewSummaryResponse> history(
            @RequestParam("symbol") String symbol,
            @RequestParam(name = "limit", required = false) Integer limit) {
        return service.findHistory(symbol, limit).map(MarketReviewSummaryResponse::from);
    }

    private static List<String> parseSymbols(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(s -> s.toUpperCase(Locale.ROOT))
                .distinct()
                .toList();
    }

    /**
     * Public response shape — flat + UI-friendly. Does not leak the metadata json blob verbatim;
     * exposes only the curated counters the UI knows how to render.
     */
    public record MarketReviewSummaryResponse(
            UUID id,
            String symbol,
            UUID runtimeRunId,
            String workflowId,
            String status,
            String verdict,
            String summaryPreview,
            String requestedBy,
            Instant startedAt,
            Instant finishedAt,
            Instant updatedAt,
            Map<String, Object> metadata) {

        public static MarketReviewSummaryResponse from(MarketReviewRun run) {
            return new MarketReviewSummaryResponse(
                    run.id(),
                    run.symbol(),
                    run.runtimeRunId(),
                    run.workflowId(),
                    run.status().name(),
                    run.verdict() == null ? null : run.verdict().name(),
                    truncate(run.summary(), 280),
                    run.requestedBy(),
                    run.startedAt(),
                    run.finishedAt(),
                    run.updatedAt(),
                    run.metadata());
        }

        private static String truncate(String value, int max) {
            if (value == null) return null;
            if (value.length() <= max) return value;
            return value.substring(0, max) + "…";
        }
    }
}
