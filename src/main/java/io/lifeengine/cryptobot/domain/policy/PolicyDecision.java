package io.lifeengine.cryptobot.domain.policy;

import java.time.Instant;
import java.util.List;

/**
 * Result of the policy layer. Two verdicts on purpose:
 *
 * <ul>
 *   <li>{@code allowed} — may this proposal be shown for approval at all (size, asset allowlist,
 *       kill switch, cooldown, and the deterministic {@link #authorization} not being DENY)?
 *       False ⇒ {@code BLOCKED_BY_POLICY}.
 *   <li>{@code executable} — may it go on-chain after approval (cluster is devnet, the signer
 *       controls the wallet, a transaction was prepared and simulated OK)? False ⇒ the proposal
 *       is a paper trade: approvable, auditable, never broadcast.
 * </ul>
 *
 * Rules are named so the audit trail can say exactly which one fired.
 *
 * <p>{@code authorization} (KAN-436) is the graduated verdict of {@link DeterministicPolicyEngine}
 * over {@code (I, S, R_v)}: ALLOW / ESCALATE / DENY with the failed predicates and the hash of
 * the policy that decided. {@code null} only on rows persisted before the verdict existed; the
 * execution preconditions treat that as "not authorized".
 */
public record PolicyDecision(
        boolean allowed,
        boolean executable,
        List<Violation> violations,
        List<Violation> executionViolations,
        List<String> rulesApplied,
        Instant evaluatedAt,
        PolicyVerdict authorization) {

    public record Violation(String rule, String message) {}

    public PolicyDecision {
        violations = violations == null ? List.of() : List.copyOf(violations);
        executionViolations = executionViolations == null ? List.of() : List.copyOf(executionViolations);
        rulesApplied = rulesApplied == null ? List.of() : List.copyOf(rulesApplied);
    }
}
