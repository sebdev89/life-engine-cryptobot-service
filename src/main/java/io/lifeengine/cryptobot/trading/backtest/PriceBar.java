package io.lifeengine.cryptobot.trading.backtest;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/** One closed candle of SOL in USD. The backtester trades at {@code close}; the bar is opened at {@code time}. */
public record PriceBar(Instant time, BigDecimal close) {

    public PriceBar {
        Objects.requireNonNull(time, "time");
        Objects.requireNonNull(close, "close");
        if (close.signum() <= 0) {
            throw new IllegalArgumentException("close must be positive: " + close);
        }
    }
}
