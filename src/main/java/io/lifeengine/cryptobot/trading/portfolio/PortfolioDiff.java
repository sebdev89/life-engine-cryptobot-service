package io.lifeengine.cryptobot.trading.portfolio;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** "What changed in my wallet?" — computed, never stored. */
public record PortfolioDiff(
        Instant previousCapturedAt,
        Instant currentCapturedAt,
        BigDecimal totalUsdBefore,
        BigDecimal totalUsdAfter,
        BigDecimal totalUsdDeltaPct,
        List<PortfolioChange> changes,
        String largestMoveSymbol,
        BigDecimal largestWeightPctDelta) {

    public PortfolioDiff {
        changes = changes == null ? List.of() : List.copyOf(changes);
    }
}
