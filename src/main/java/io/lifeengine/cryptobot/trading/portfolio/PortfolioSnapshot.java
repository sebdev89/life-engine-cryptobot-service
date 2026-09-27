package io.lifeengine.cryptobot.trading.portfolio;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Point-in-time valuation of a wallet. Persisted so "what changed?" has something to diff against. */
public record PortfolioSnapshot(
        UUID id,
        UUID walletId,
        Instant capturedAt,
        BigDecimal totalUsd,
        List<Position> positions,
        String priceSource,
        int recentTxCount) {

    public PortfolioSnapshot {
        positions = positions == null ? List.of() : List.copyOf(positions);
    }

    public Optional<Position> position(String symbol) {
        return positions.stream().filter(p -> p.symbol().equalsIgnoreCase(symbol)).findFirst();
    }

    public Optional<Position> largest() {
        return positions.stream().filter(Position::priced).max((a, b) -> a.valueUsd().compareTo(b.valueUsd()));
    }
}
