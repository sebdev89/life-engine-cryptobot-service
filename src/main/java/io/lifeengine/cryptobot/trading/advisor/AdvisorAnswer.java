package io.lifeengine.cryptobot.trading.advisor;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** Parsed {@code crypto.portfolio-advisor-output.v1}. The agent explains; it never sets amounts. */
public record AdvisorAnswer(
        String answer,
        List<KeyRisk> keyRisks,
        List<SuggestedAction> suggestedActions,
        BigDecimal confidence,
        String disclaimer,
        String promptVersion,
        UUID runtimeRunId,
        String model) {

    public record KeyRisk(String title, String severity, String why) {}

    public record SuggestedAction(String action, String asset, BigDecimal targetWeightPct, String rationale) {}

    public AdvisorAnswer {
        keyRisks = keyRisks == null ? List.of() : List.copyOf(keyRisks);
        suggestedActions = suggestedActions == null ? List.of() : List.copyOf(suggestedActions);
    }
}
