package io.lifeengine.cryptobot.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record PriceZone(
        UUID id,
        String symbol,
        String timeframe,
        String zoneKind,
        BigDecimal lowerBound,
        BigDecimal upperBound,
        BigDecimal confidence,
        Instant validFrom,
        Instant validUntil,
        String label,
        Instant createdAt,
        Instant updatedAt) {}
