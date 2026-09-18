package io.lifeengine.cryptobot.validator.policy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The output of {@link DeterministicPolicyEngine}: a discrete decision (paper §18), never text.
 *
 * <ul>
 *   <li>{@link Decision#ALLOW} — every predicate holds and the trade is inside the autonomous
 *       tier; the system may execute without further authorization.
 *   <li>{@link Decision#ESCALATE} — every predicate holds but the tier demands more authority:
 *       {@link Escalation#REQUIRE_SECOND_AGENT} or {@link Escalation#REQUIRE_HUMAN_SIGNATURE}.
 *   <li>{@link Decision#DENY} — at least one predicate failed ({@link #failedPredicates} says
 *       which, in evaluation order) or the trade is over the hard limit.
 * </ul>
 *
 * <p>{@link #policyHash} is {@code H_R} of the rules that produced this verdict and
 * {@link #inputHash} identifies the exact {@code (I, S)}; {@link #hash()} covers the whole verdict,
 * so two implementations (or two runs) that agree produce the same hash — that is the
 * reproducibility check, and what a receipt commits to.
 */
public record PolicyVerdict(
        Decision decision,
        Escalation escalation,
        AutonomyTier tier,
        List<PolicyPredicate> failedPredicates,
        List<PolicyPredicate> evaluatedPredicates,
        String policyVersion,
        String policyHash,
        String inputHash) {

    public enum Decision {
        ALLOW,
        DENY,
        ESCALATE
    }

    public enum Escalation {
        NONE,
        REQUIRE_SECOND_AGENT,
        REQUIRE_HUMAN_SIGNATURE
    }

    /** Which tier of §18 the trade value falls in, regardless of whether the predicates held. */
    public enum AutonomyTier {
        AUTONOMOUS,
        SECOND_AGENT,
        HUMAN_SIGNATURE,
        OVER_LIMIT
    }

    public PolicyVerdict {
        failedPredicates = failedPredicates == null ? List.of() : List.copyOf(failedPredicates);
        evaluatedPredicates = evaluatedPredicates == null ? List.of() : List.copyOf(evaluatedPredicates);
    }

    public boolean allowed() {
        return decision == Decision.ALLOW;
    }

    public boolean denied() {
        return decision == Decision.DENY;
    }

    public Map<String, Object> canonicalMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("schema_version", PolicyRules.SCHEMA_VERSION);
        m.put("decision", decision.name());
        m.put("escalation", escalation.name());
        m.put("tier", tier.name());
        m.put("failed_predicates", failedPredicates.stream().map(Enum::name).toList());
        m.put("evaluated_predicates", evaluatedPredicates.stream().map(Enum::name).toList());
        m.put("policy_version", policyVersion);
        m.put("policy_hash", policyHash);
        m.put("input_hash", inputHash);
        return m;
    }

    public String canonicalJson() {
        return CanonicalJson.canonicalize(canonicalMap());
    }

    /** {@code SHA-256} of the canonical verdict — the {@code outputHash} of a RISK/POLICY receipt. */
    public String hash() {
        return CanonicalJson.sha256(canonicalJson());
    }
}
