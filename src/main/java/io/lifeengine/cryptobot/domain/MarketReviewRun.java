package io.lifeengine.cryptobot.domain;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Local linkage row for one cryptobot market-review execution. Persisted in
 * {@code market_review_run} (see Flyway V3).
 *
 * <p>Source-of-truth for workflow execution is Runtime; {@code runtimeRunId} is the join key.
 * Reconciliation populates {@link #verdict}, {@link #summary} and {@link #metadata} from
 * {@code GET /api/runtime/runs/{runId}}.
 */
public record MarketReviewRun(
        UUID id,
        String symbol,
        UUID runtimeRunId,
        String workflowId,
        MarketReviewRunStatus status,
        String requestedBy,
        Instant startedAt,
        Instant finishedAt,
        MarketReviewVerdict verdict,
        String summary,
        UUID linkedJournalId,
        UUID linkedObservationId,
        Map<String, Object> metadata,
        Instant createdAt,
        Instant updatedAt) {

    public MarketReviewRun {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public MarketReviewRun withReconciledTerminalState(
            MarketReviewRunStatus newStatus,
            Instant newFinishedAt,
            MarketReviewVerdict newVerdict,
            String newSummary,
            Map<String, Object> newMetadata) {
        Map<String, Object> mergedMetadata = new LinkedHashMap<>(this.metadata);
        if (newMetadata != null) {
            mergedMetadata.putAll(newMetadata);
        }
        return new MarketReviewRun(
                id,
                symbol,
                runtimeRunId,
                workflowId,
                newStatus,
                requestedBy,
                startedAt,
                newFinishedAt != null ? newFinishedAt : finishedAt,
                newVerdict != null ? newVerdict : verdict,
                newSummary != null ? newSummary : summary,
                linkedJournalId,
                linkedObservationId,
                Map.copyOf(mergedMetadata),
                createdAt,
                Instant.now());
    }
}
