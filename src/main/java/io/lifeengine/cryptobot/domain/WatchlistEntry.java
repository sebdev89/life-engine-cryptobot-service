package io.lifeengine.cryptobot.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Curated symbol in the cryptobot watchlist. Mirrors the observability MVP entity
 * inherited from the modulith (now flattened to schema {@code public}).
 */
public record WatchlistEntry(
        UUID id,
        String symbol,
        String displayName,
        String assetType,
        String sectorTheme,
        String exchange,
        int priority,
        boolean active,
        String notes,
        Instant createdAt,
        Instant updatedAt) {}
