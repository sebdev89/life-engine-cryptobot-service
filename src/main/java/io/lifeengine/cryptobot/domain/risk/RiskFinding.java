package io.lifeengine.cryptobot.domain.risk;

import java.math.BigDecimal;

/** One deterministic rule that fired. {@code metric} vs {@code threshold} is what the UI shows. */
public record RiskFinding(
        String code,
        RiskSeverity severity,
        String title,
        String detail,
        String asset,
        BigDecimal metric,
        BigDecimal threshold) {}
