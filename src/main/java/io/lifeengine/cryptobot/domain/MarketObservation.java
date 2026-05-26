package io.lifeengine.cryptobot.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Append-only point-in-time venue snapshot. Persisted as analytics, not as orders. */
public record MarketObservation(
        UUID id,
        String symbol,
        String venue,
        Instant observedAt,
        String timeframe,
        BigDecimal lastPrice,
        BigDecimal changePct24h,
        BigDecimal volumeQuote24h,
        BigDecimal spreadBps,
        BigDecimal liquidityScore,
        String regime,
        Instant createdAt) {}
