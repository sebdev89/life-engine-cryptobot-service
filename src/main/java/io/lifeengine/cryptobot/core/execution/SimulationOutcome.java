package io.lifeengine.cryptobot.core.execution;

import java.math.BigDecimal;
import java.util.List;

/**
 * Two simulations, two questions. Economic: "what would I get?" (spot × amount, fee, impact).
 * On-chain: "would this exact transaction succeed?" ({@code simulateTransaction}).
 */
public record SimulationOutcome(Economic economic, Onchain onchain) {

    public record Economic(
            String sellSymbol,
            BigDecimal sellAmount,
            String buySymbol,
            BigDecimal expectedBuyAmount,
            BigDecimal priceUsd,
            BigDecimal estimatedFeeSol,
            BigDecimal priceImpactPct,
            String source) {}

    public record Onchain(boolean ok, String error, Long unitsConsumed, List<String> logs, String cluster) {
        public Onchain {
            logs = logs == null ? List.of() : List.copyOf(logs);
        }
    }

    public boolean passed() {
        return onchain != null && onchain.ok();
    }
}
