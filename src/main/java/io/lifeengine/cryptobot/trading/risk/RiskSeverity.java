package io.lifeengine.cryptobot.trading.risk;

public enum RiskSeverity {
    LOW,
    MEDIUM,
    HIGH;

    public boolean atLeast(RiskSeverity other) {
        return ordinal() >= other.ordinal();
    }
}
