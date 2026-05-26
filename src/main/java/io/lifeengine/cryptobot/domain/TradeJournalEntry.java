package io.lifeengine.cryptobot.domain;

import java.time.Instant;
import java.util.UUID;

public record TradeJournalEntry(
        UUID id,
        String symbol,
        Instant entryTime,
        String title,
        String body,
        String sentiment,
        String tags,
        UUID linkedWatchlistId,
        Instant createdAt,
        Instant updatedAt) {}
