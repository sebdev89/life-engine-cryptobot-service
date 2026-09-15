package io.lifeengine.cryptobot.domain.policy;

import java.time.Instant;
import java.util.List;

/**
 * Result of the policy layer. Two verdicts on purpose:
 *
 * <ul>
 *   <li>{@code allowed} — may this proposal be shown for approval at all (size, asset allowlist,
 *       kill switch, cooldown)? False ⇒ {@code BLOCKED_BY_POLICY}.
 *   <li>{@code executable} — may it go on-chain after approval (cluster is devnet, the signer
 *       controls the wallet, a transaction was prepared and simulated OK)? False ⇒ the proposal
 *       is a paper trade: approvable, auditable, never broadcast.
 * </ul>
 *
 * Rules are named so the audit trail can say exactly which one fired.
 */
public record PolicyDecision(
        boolean allowed,
        boolean executable,
        List<Violation> violations,
        List<Violation> executionViolations,
        List<String> rulesApplied,
        Instant evaluatedAt) {

    public record Violation(String rule, String message) {}

    public PolicyDecision {
        violations = violations == null ? List.of() : List.copyOf(violations);
        executionViolations = executionViolations == null ? List.of() : List.copyOf(executionViolations);
        rulesApplied = rulesApplied == null ? List.of() : List.copyOf(rulesApplied);
    }
}
