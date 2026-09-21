package io.lifeengine.cryptobot.signer.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * The signer's own series (KAN-582, HK-5b). Until now "firmados" on the demo dashboard was a
 * derived value ({@code validator_attestations_total − trade_failed_total}) because the signer
 * published nothing of its own. Now it says what it signed and what it refused, by rule.
 *
 * <pre>
 *   signer.signatures     → signer_signatures_total{outcome,rule,kind}
 *       outcome = signed | refused
 *       rule    = none (signed) | the refusal reason of SigningPolicy / AttestationVerifier
 *                 (signer_disabled, mainnet_disabled, destination_not_allowed, amount_over_cap,
 *                 attestation_missing, attestation_expired, …) | other (anything not in KNOWN_RULES)
 *       kind    = transfer (/sign, the demo path) | anchor (/sign-anchor, receipt batches, KAN-394)
 *   signer.sign.latency   → signer_sign_latency_seconds{kind}   histogram: policy + attestation check + Ed25519
 * </pre>
 *
 * Bounded on purpose: the rule label only ever takes a value from {@link #KNOWN_RULES}; a new
 * refusal reason in the policy shows up as {@code other} until it is added here (and to the
 * placeholders, so the panel reads 0 instead of "No data"). Never a proposal id, a key or a signature.
 * {@code environment}/{@code service}/{@code version}/{@code commit} come from {@link BuildIdentityCommonTags}.
 */
@Component
public class SignerMetrics {

    static final String SIGNATURES = "signer.signatures";
    static final String SIGN_LATENCY = "signer.sign.latency";

    public static final String OUTCOME_SIGNED = "signed";
    public static final String OUTCOME_REFUSED = "refused";
    public static final String KIND_TRANSFER = "transfer";
    public static final String KIND_ANCHOR = "anchor";
    public static final String RULE_NONE = "none";
    public static final String RULE_OTHER = "other";

    /** Every refusal reason {@code SigningPolicy} and {@code AttestationVerifier} can produce, plus the request-level ones. */
    public static final List<String> KNOWN_RULES = List.of(
            // request
            "missing_transaction",
            // SigningPolicy (transfer and anchor)
            "signer_disabled", "undecodable_transaction", "multiple_signers", "fee_payer_mismatch", "expected_fee_payer_mismatch",
            "instruction_count", "program_not_allowed", "not_a_transfer", "transfer_source_mismatch", "destination_not_allowed",
            "amount_over_cap", "cluster_missing", "cluster_unknown", "mainnet_disabled", "cluster_mismatch",
            "anchor_cluster_not_devnet", "memo_accounts_not_allowed", "memo_format", "memo_mismatch",
            // AttestationVerifier (KAN-438)
            "validator_key_not_configured", "attestation_missing", "attestation_bad_signature", "attestation_unparseable",
            "attestation_validator_mismatch", "attestation_proposal_mismatch", "attestation_message_mismatch", "attestation_denied",
            "attestation_no_window", "attestation_not_yet_valid", "attestation_expired", "attestation_cluster_missing",
            "attestation_cluster_mismatch");

    private static final Set<String> KNOWN = Set.copyOf(KNOWN_RULES);
    private static final Duration[] LATENCY_BUCKETS = {
        Duration.ofMillis(5), Duration.ofMillis(10), Duration.ofMillis(25), Duration.ofMillis(50), Duration.ofMillis(100),
        Duration.ofMillis(250), Duration.ofMillis(500), Duration.ofSeconds(1), Duration.ofMillis(2500)
    };

    private final MeterRegistry registry;

    public SignerMetrics(MeterRegistry registry) {
        this.registry = registry;
        registerPlaceholders();
    }

    /** For tests and for wiring that must not depend on the Spring context. Nothing is exported. */
    public static SignerMetrics noop() {
        return new SignerMetrics(new SimpleMeterRegistry());
    }

    /** One signature produced: {@code kind} transfer (the demo path) or anchor. */
    public void signed(String kind) {
        counter(OUTCOME_SIGNED, RULE_NONE, kind(kind)).increment();
    }

    /** One refusal, by the rule that said no (sanitised to the bounded set). */
    public void refused(String kind, String reason) {
        counter(OUTCOME_REFUSED, rule(reason), kind(kind)).increment();
    }

    /** Starts the clock over {@code /sign} or {@code /sign-anchor}. */
    public Timer.Sample start() {
        return Timer.start(registry);
    }

    public void stop(String kind, Timer.Sample sample) {
        if (sample != null) {
            sample.stop(latency(kind(kind)));
        }
    }

    /** Public so the meter-names test can assert the exact label value the dashboard will query. */
    public static String rule(String reason) {
        if (reason == null || reason.isBlank()) {
            return RULE_OTHER;
        }
        // "undecodable_transaction: <exception message>" → the rule, never the free text.
        String head = reason.trim();
        int colon = head.indexOf(':');
        if (colon > 0) {
            head = head.substring(0, colon);
        }
        head = head.trim().toLowerCase(Locale.ROOT);
        return KNOWN.contains(head) ? head : RULE_OTHER;
    }

    private static String kind(String kind) {
        return KIND_ANCHOR.equals(kind) ? KIND_ANCHOR : KIND_TRANSFER;
    }

    private Counter counter(String outcome, String rule, String kind) {
        return Counter.builder(SIGNATURES)
                .description("Signatures produced or refused by the isolated signer, by rule (KAN-582)")
                .tags("outcome", outcome, "rule", rule, "kind", kind)
                .register(registry);
    }

    private Timer latency(String kind) {
        return Timer.builder(SIGN_LATENCY)
                .description("Time to policy-check, verify the attestation and sign one transaction (KAN-582)")
                .tag("kind", kind)
                .serviceLevelObjectives(LATENCY_BUCKETS)
                .register(registry);
    }

    /** The series the demo dashboard reads exist at 0 from boot: "0 refused by mainnet" is measured, not missing. */
    private void registerPlaceholders() {
        counter(OUTCOME_SIGNED, RULE_NONE, KIND_TRANSFER);
        counter(OUTCOME_SIGNED, RULE_NONE, KIND_ANCHOR);
        for (String rule : KNOWN_RULES) {
            counter(OUTCOME_REFUSED, rule, KIND_TRANSFER);
        }
        counter(OUTCOME_REFUSED, RULE_OTHER, KIND_TRANSFER);
        latency(KIND_TRANSFER);
        latency(KIND_ANCHOR);
    }
}
