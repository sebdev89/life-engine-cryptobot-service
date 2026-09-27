package io.lifeengine.cryptobot.trading.risk;

import java.time.Instant;
import java.util.List;

/**
 * Output of the rules engine for one snapshot. {@code score} is 0 (calm) to 100 (act now).
 * {@code findings} are the signals of {@link #decision} rendered with prose for the UI;
 * {@code decision} is the deterministic core (KAN-392): canonical input, canonical verdict,
 * engine version and weights hash — what the {@code RISK_DECISION} receipt hashes and what
 * {@code verify} re-executes. {@code null} only for {@link #none}.
 */
public record RiskReport(List<RiskFinding> findings, RiskSeverity overall, int score, Instant evaluatedAt, DeterministicDecision decision) {

    public RiskReport {
        findings = findings == null ? List.of() : List.copyOf(findings);
    }

    public static RiskReport none(Instant at) {
        return new RiskReport(List.of(), RiskSeverity.LOW, 0, at, null);
    }
}
