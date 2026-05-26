package io.lifeengine.cryptobot.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Append-only computed indicator value (RSI / EMA / MACD / etc.) at a point in time. */
public record IndicatorSnapshot(
        UUID id,
        String symbol,
        String timeframe,
        String indicatorName,
        Integer period,
        Instant computedAt,
        BigDecimal valueNumeric,
        String valueJson,
        Instant createdAt) {}
