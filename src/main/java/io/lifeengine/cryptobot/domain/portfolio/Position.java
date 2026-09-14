package io.lifeengine.cryptobot.domain.portfolio;

import java.math.BigDecimal;

/** One asset line of a valued portfolio. Amounts are UI units (already divided by decimals). */
public record Position(
        String mint,
        String symbol,
        BigDecimal amount,
        int decimals,
        BigDecimal priceUsd,
        BigDecimal valueUsd,
        BigDecimal weightPct,
        boolean stable,
        boolean nativeSol,
        String priceSource) {

    public boolean priced() {
        return priceUsd != null;
    }
}
