package io.lifeengine.cryptobot.trading.strategy;

import java.math.BigDecimal;
import java.util.Map;

/** "Bring SOL down to 50%". Keys are symbols; values are target weights in percent. */
public record RebalanceIntent(Map<String, BigDecimal> targetWeights, String counterAsset) {

    public RebalanceIntent {
        targetWeights = targetWeights == null ? Map.of() : Map.copyOf(targetWeights);
        counterAsset = counterAsset == null || counterAsset.isBlank() ? "USDC" : counterAsset.trim().toUpperCase();
    }
}
