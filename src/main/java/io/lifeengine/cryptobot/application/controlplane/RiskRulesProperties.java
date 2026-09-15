package io.lifeengine.cryptobot.application.controlplane;

import java.math.BigDecimal;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Thresholds of the deterministic risk rules. Percentages are 0–100. */
@ConfigurationProperties(prefix = "cryptobot.risk")
public record RiskRulesProperties(
        BigDecimal concentrationHighPct,
        BigDecimal concentrationMediumPct,
        BigDecimal minStablePct,
        BigDecimal dustUsd,
        BigDecimal sharpMovePct) {

    public RiskRulesProperties {
        concentrationHighPct = concentrationHighPct == null ? new BigDecimal("60") : concentrationHighPct;
        concentrationMediumPct = concentrationMediumPct == null ? new BigDecimal("40") : concentrationMediumPct;
        minStablePct = minStablePct == null ? new BigDecimal("10") : minStablePct;
        dustUsd = dustUsd == null ? new BigDecimal("1") : dustUsd;
        sharpMovePct = sharpMovePct == null ? new BigDecimal("5") : sharpMovePct;
    }
}
