package io.lifeengine.cryptobot.trading.strategy;

import java.math.BigDecimal;

/** One trade the planner derived from the intent. Amount is in UI units of {@code symbol}. */
public record RebalanceLeg(
        Action action,
        String symbol,
        String mint,
        BigDecimal amount,
        BigDecimal estimatedUsd,
        BigDecimal weightPctBefore,
        BigDecimal weightPctAfter,
        String counterAsset) {

    public enum Action {
        SELL,
        BUY
    }
}
