package io.lifeengine.cryptobot.trading.strategy;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** Deterministic output of {@code RebalancePlanner}; what the human sees and approves. */
public record RebalancePlan(
        List<RebalanceLeg> legs,
        BigDecimal totalUsd,
        Map<String, BigDecimal> weightsBefore,
        Map<String, BigDecimal> weightsAfter,
        BigDecimal turnoverUsd,
        String summary) {

    public RebalancePlan {
        legs = legs == null ? List.of() : List.copyOf(legs);
        weightsBefore = weightsBefore == null ? Map.of() : Map.copyOf(weightsBefore);
        weightsAfter = weightsAfter == null ? Map.of() : Map.copyOf(weightsAfter);
    }

    public boolean isNoop() {
        return legs.isEmpty();
    }
}
