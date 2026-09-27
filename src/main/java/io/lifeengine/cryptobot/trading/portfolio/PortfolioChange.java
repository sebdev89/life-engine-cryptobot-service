package io.lifeengine.cryptobot.trading.portfolio;

import java.math.BigDecimal;

/** Per-asset delta between two snapshots. Nulls mean the asset was absent on that side. */
public record PortfolioChange(
        String symbol,
        BigDecimal amountBefore,
        BigDecimal amountAfter,
        BigDecimal valueUsdBefore,
        BigDecimal valueUsdAfter,
        BigDecimal weightPctBefore,
        BigDecimal weightPctAfter,
        BigDecimal weightPctDelta) {}
