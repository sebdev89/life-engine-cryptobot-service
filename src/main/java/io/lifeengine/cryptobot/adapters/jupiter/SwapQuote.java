package io.lifeengine.cryptobot.adapters.jupiter;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.List;

/**
 * A Jupiter quote for an exact-in swap, checked against what was asked for. {@code minOutAmount} is Jupiter's
 * {@code otherAmountThreshold}: the least the swap may return once slippage is applied, enforced on chain by the
 * Jupiter program. {@code raw} is the response as received, because the swap endpoint takes the quote back verbatim.
 */
public record SwapQuote(
        String inputMint,
        String outputMint,
        long inAmount,
        long outAmount,
        long minOutAmount,
        int slippageBps,
        BigDecimal priceImpactPct,
        List<String> routeLabels,
        JsonNode raw) {

    public SwapQuote {
        routeLabels = routeLabels == null ? List.of() : List.copyOf(routeLabels);
    }
}
