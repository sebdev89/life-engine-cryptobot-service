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
 *
 * <p>{@code input} (KAN-438) is the exact {@code (I, S)} the verdict was computed over, kept so
 * the independent validator can re-derive the verdict in its own process at execution time and
 * refuse if it disagrees. {@code null} on rows persisted before KAN-438: nothing to re-validate ⇒
 * not executable (fail-closed).
 */
public record PolicyDecision(
        boolean allowed,
        boolean executable,
        List<Violation> violations,
        List<Violation> executionViolations,
        List<String> rulesApplied,
        Instant evaluatedAt,
        PolicyVerdict authorization,
        PolicyInput input) {

    public record Violation(String rule, String message) {}

    @com.fasterxml.jackson.annotation.JsonCreator
    public PolicyDecision {
        violations = violations == null ? List.of() : List.copyOf(violations);
        executionViolations = executionViolations == null ? List.of() : List.copyOf(executionViolations);
        rulesApplied = rulesApplied == null ? List.of() : List.copyOf(rulesApplied);
    }

    /** Pre-KAN-438 shape: no recorded input. */
    public PolicyDecision(boolean allowed, boolean executable, List<Violation> violations, List<Violation> executionViolations,
            List<String> rulesApplied, Instant evaluatedAt, PolicyVerdict authorization) {
        this(allowed, executable, violations, executionViolations, rulesApplied, evaluatedAt, authorization, null);
    }

    /** The same decision with one more execution rule failed: {@code executable} becomes false. */
    public PolicyDecision withExecutionViolation(Violation violation) {
        List<Violation> ev = new java.util.ArrayList<>(executionViolations);
        ev.add(violation);
        List<String> applied = new java.util.ArrayList<>(rulesApplied);
        if (!applied.contains(violation.rule())) {
            applied.add(violation.rule());
        }
        return new PolicyDecision(allowed, false, violations, ev, applied, evaluatedAt, authorization, input);
    }

    /** The same decision with a rule recorded as applied and passed. */
    public PolicyDecision withRuleApplied(String rule) {
        if (rulesApplied.contains(rule)) {
            return this;
        }
        List<String> applied = new java.util.ArrayList<>(rulesApplied);
        applied.add(rule);
        return new PolicyDecision(allowed, executable, violations, executionViolations, applied, evaluatedAt, authorization, input);
    }
}
