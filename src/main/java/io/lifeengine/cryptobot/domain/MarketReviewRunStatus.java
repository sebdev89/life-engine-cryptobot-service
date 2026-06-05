package io.lifeengine.cryptobot.domain;

import java.util.Set;

/** Mirrors the {@code chk_market_review_run_status} constraint in V3. */
public enum MarketReviewRunStatus {
    PENDING,
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELLED;

    private static final Set<MarketReviewRunStatus> TERMINAL =
            Set.of(SUCCEEDED, FAILED, CANCELLED);

    public boolean isTerminal() {
        return TERMINAL.contains(this);
    }

    /**
     * Maps a runtime {@code RunStatus} string to its local cryptobot equivalent. Runtime statuses
     * we observe via {@code GET /api/runtime/runs/{runId}} are RUNNING / SUCCEEDED / FAILED /
     * CANCELLED (with PENDING used internally before the first event). Anything we don't recognise
     * is treated as RUNNING so reconciliation can keep polling.
     */
    public static MarketReviewRunStatus fromRuntime(String runtimeStatus) {
        if (runtimeStatus == null || runtimeStatus.isBlank()) {
            return RUNNING;
        }
        return switch (runtimeStatus.trim().toUpperCase(java.util.Locale.ROOT)) {
            case "SUCCEEDED" -> SUCCEEDED;
            case "FAILED" -> FAILED;
            case "CANCELLED" -> CANCELLED;
            case "PENDING" -> PENDING;
            default -> RUNNING;
        };
    }
}
