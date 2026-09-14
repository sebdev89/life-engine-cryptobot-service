package io.lifeengine.cryptobot.domain.risk;

import java.time.Instant;
import java.util.List;

/** Output of the rules engine for one snapshot. {@code score} is 0 (calm) to 100 (act now). */
public record RiskReport(List<RiskFinding> findings, RiskSeverity overall, int score, Instant evaluatedAt) {

    public RiskReport {
        findings = findings == null ? List.of() : List.copyOf(findings);
    }

    public static RiskReport none(Instant at) {
        return new RiskReport(List.of(), RiskSeverity.LOW, 0, at);
    }
}
