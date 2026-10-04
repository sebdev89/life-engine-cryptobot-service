package io.lifeengine.cryptobot.validator.observability;

import io.lifeengine.cryptobot.validator.policy.PolicyPredicate;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * The validator's own series (HK-5b). cryptobot-service already counts what it
 * <em>received</em> ({@code validator_attestations_total{result}} on its side); this is what the
 * validator <em>decided</em>, by the rule that decided it.
 *
 * <pre>
 *   validator.attestations     → validator_attestations_total{outcome,rule}
 *       outcome = issued (ALLOW or ESCALATE: an attestation the signer will accept)
 *               | denied (DENY: attested, but the signer refuses it)
 *               | malformed (400: nothing attested)
 *       rule    = none (issued)
 *               | validator_disabled | policy_hash_mismatch | verdict_disagreement   (the validator's own refusals)
 *               | a failed predicate of R_v, lowercased (policy_bound, asset_allowed, trade_within_max, daily_limit,
 *                 asset_concentration, slippage_within_max, oracle_fresh, agent_permitted, strategy_enabled,
 *                 nonce_unused, not_expired) — the FIRST one, so one attestation is one increment
 *               | missing_body | missing_proposal_id | missing_or_invalid_message_hash | missing_policy_hash
 *                 | missing_or_invalid_cluster (malformed)
 *               | other
 *   validator.predicate.failed → validator_predicate_failed_total{predicate}   one per failed predicate of a DENY (a verdict may count several)
 *   validator.validate.latency → validator_validate_latency_seconds            histogram: re-derive the verdict + sign the attestation
 * </pre>
 *
 * Same process label set as cryptobot-service and cryptobot-signer ({@code environment}/{@code service}/
 * {@code version}/{@code commit} from {@link BuildIdentityCommonTags}); the {@code service} tag is what
 * keeps this {@code validator_attestations_total} apart from the service's on the dashboard.
 * Bounded: every label value is from a closed list; never a proposal id, a hash or a key.
 */
@Component
public class ValidatorMetrics {

    static final String ATTESTATIONS = "validator.attestations";
    static final String PREDICATE_FAILED = "validator.predicate.failed";
    static final String VALIDATE_LATENCY = "validator.validate.latency";

    public static final String OUTCOME_ISSUED = "issued";
    public static final String OUTCOME_DENIED = "denied";
    public static final String OUTCOME_MALFORMED = "malformed";
    public static final String RULE_NONE = "none";
    public static final String RULE_OTHER = "other";

    /** The validator's own refusals (ValidationService.REFUSAL_*), as label values. */
    public static final List<String> REFUSAL_RULES = List.of("validator_disabled", "policy_hash_mismatch", "verdict_disagreement");
    /** What {@code ValidationService.MalformedRequest} can say. */
    public static final List<String> MALFORMED_RULES = List.of("missing_body", "missing_proposal_id", "missing_or_invalid_message_hash",
            "missing_policy_hash", "missing_or_invalid_cluster");

    private static final Set<String> KNOWN;
    private static final Duration[] LATENCY_BUCKETS = {
        Duration.ofMillis(5), Duration.ofMillis(10), Duration.ofMillis(25), Duration.ofMillis(50), Duration.ofMillis(100),
        Duration.ofMillis(250), Duration.ofMillis(500), Duration.ofSeconds(1), Duration.ofMillis(2500)
    };

    static {
        List<String> all = new ArrayList<>(REFUSAL_RULES);
        all.addAll(MALFORMED_RULES);
        for (PolicyPredicate p : PolicyPredicate.values()) {
            all.add(p.name().toLowerCase(Locale.ROOT));
        }
        KNOWN = Set.copyOf(all);
    }

    private final MeterRegistry registry;

    public ValidatorMetrics(MeterRegistry registry) {
        this.registry = registry;
        registerPlaceholders();
    }

    /** For tests and for wiring that must not depend on the Spring context. Nothing is exported. */
    public static ValidatorMetrics noop() {
        return new ValidatorMetrics(new SimpleMeterRegistry());
    }

    /**
     * One verdict attested. {@code decision} ALLOW | ESCALATE | DENY; {@code refusals} the validator's own
     * (may be empty); {@code failedPredicates} of the re-derived verdict (may be empty).
     */
    public void attested(String decision, List<String> refusals, List<String> failedPredicates) {
        boolean issued = "ALLOW".equals(decision) || "ESCALATE".equals(decision);
        String rule = RULE_NONE;
        if (!issued) {
            if (refusals != null && !refusals.isEmpty()) {
                rule = rule(refusals.get(0));
            } else if (failedPredicates != null && !failedPredicates.isEmpty()) {
                rule = rule(failedPredicates.get(0));
            } else {
                rule = RULE_OTHER;
            }
        }
        counter(issued ? OUTCOME_ISSUED : OUTCOME_DENIED, rule).increment();
        if (failedPredicates != null) {
            for (String predicate : failedPredicates) {
                predicateFailed(rule(predicate)).increment();
            }
        }
    }

    /** A request the validator could not evaluate (400). */
    public void malformed(String reason) {
        counter(OUTCOME_MALFORMED, rule(reason)).increment();
    }

    public Timer.Sample start() {
        return Timer.start(registry);
    }

    public void stop(Timer.Sample sample) {
        if (sample != null) {
            sample.stop(latency());
        }
    }

    /** Public so the meter-names test can assert the exact label value the dashboard will query. */
    public static String rule(String reason) {
        if (reason == null || reason.isBlank()) {
            return RULE_OTHER;
        }
        String v = reason.trim().toLowerCase(Locale.ROOT);
        return KNOWN.contains(v) ? v : RULE_OTHER;
    }

    private Counter counter(String outcome, String rule) {
        return Counter.builder(ATTESTATIONS)
                .description("Attestations issued or denied by the independent validator, by rule (KAN-582)")
                .tags("outcome", outcome, "rule", rule)
                .register(registry);
    }

    private Counter predicateFailed(String predicate) {
        return Counter.builder(PREDICATE_FAILED)
                .description("Failed predicates of R_v as re-derived by the independent validator (KAN-582)")
                .tag("predicate", predicate)
                .register(registry);
    }

    private Timer latency() {
        return Timer.builder(VALIDATE_LATENCY)
                .description("Time to re-derive the verdict over (I, S, R_v) and sign the attestation (KAN-582)")
                .serviceLevelObjectives(LATENCY_BUCKETS)
                .register(registry);
    }

    /** The series the demo dashboard reads exist at 0 from boot. */
    private void registerPlaceholders() {
        counter(OUTCOME_ISSUED, RULE_NONE);
        for (String r : REFUSAL_RULES) {
            counter(OUTCOME_DENIED, r);
        }
        for (PolicyPredicate p : PolicyPredicate.values()) {
            counter(OUTCOME_DENIED, p.name().toLowerCase(Locale.ROOT));
            predicateFailed(p.name().toLowerCase(Locale.ROOT));
        }
        counter(OUTCOME_DENIED, RULE_OTHER);
        for (String r : MALFORMED_RULES) {
            counter(OUTCOME_MALFORMED, r);
        }
        latency();
    }
}
