package io.lifeengine.cryptobot.domain;

import java.util.Map;

public record MarketSignal(String signal, String strength, String reason, Map<String, Double> indicators) {

    public MarketSignal {
        indicators = indicators == null ? Map.of() : Map.copyOf(indicators);
    }
}
