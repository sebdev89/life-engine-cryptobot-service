package io.lifeengine.cryptobot.domain;

import java.util.Locale;

/** Mirrors the {@code chk_market_review_run_verdict} constraint in V3. */
public enum MarketReviewVerdict {
    BULLISH,
    BEARISH,
    NEUTRAL,
    UNKNOWN;

    /**
     * Maps the {@code bias} field emitted by the runtime analyst stage
     * ({@code crypto-market-analyst-agent}) to the local verdict enum. Returns {@link #UNKNOWN} for
     * anything we cannot classify (including {@code null}).
     */
    public static MarketReviewVerdict fromAnalystBias(String bias) {
        if (bias == null || bias.isBlank()) {
            return UNKNOWN;
        }
        return switch (bias.trim().toUpperCase(Locale.ROOT)) {
            case "BULLISH" -> BULLISH;
            case "BEARISH" -> BEARISH;
            case "NEUTRAL" -> NEUTRAL;
            default -> UNKNOWN;
        };
    }
}
