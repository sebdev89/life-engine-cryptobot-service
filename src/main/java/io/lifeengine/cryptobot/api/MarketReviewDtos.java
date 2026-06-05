package io.lifeengine.cryptobot.api;

import io.lifeengine.cryptobot.domain.MarketSnapshot;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Canonical DTOs for {@code /api/cryptobot/market-review}. See docs/extraction/contracts.md §3. */
public final class MarketReviewDtos {

    private MarketReviewDtos() {}

    public record MarketReviewRequest(String symbol, String correlationId) {}

    public record MarketReviewResponse(
            String marketReviewId,
            String symbol,
            MarketSnapshot snapshot,
            MarketSignalResponse signal,
            RelatedRuntimeRunResponse related,
            Instant createdAt,
            /**
             * Local cryptobot-side linkage row id ({@code market_review_run.id}). Null only when
             * persistence is unavailable; the synchronous response itself is unaffected.
             */
            UUID marketReviewRunId) {

        public MarketReviewResponse(
                String marketReviewId,
                String symbol,
                MarketSnapshot snapshot,
                MarketSignalResponse signal,
                RelatedRuntimeRunResponse related,
                Instant createdAt) {
            this(marketReviewId, symbol, snapshot, signal, related, createdAt, null);
        }
    }

    public record MarketSignalResponse(
            String signal, String strength, String reason, Map<String, Double> indicators) {

        public MarketSignalResponse {
            indicators = indicators == null ? Map.of() : Map.copyOf(indicators);
        }
    }

    public record RelatedRuntimeRunResponse(
            UUID runtimeRunId,
            String runtimeWorkflowId,
            String runtimeCorrelationId,
            String runtimeBaseUrl,
            String ssePath) {}

    public record ApiError(String code, String message) {}
}
