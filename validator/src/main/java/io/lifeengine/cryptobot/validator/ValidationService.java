package io.lifeengine.cryptobot.validator;

import io.lifeengine.cryptobot.validator.observability.LogFields;
import io.lifeengine.cryptobot.validator.crypto.Base58;
import io.lifeengine.cryptobot.validator.policy.CanonicalJson;
import io.lifeengine.cryptobot.validator.policy.IndependentPolicyTable;
import io.lifeengine.cryptobot.validator.policy.PolicyInput;
import io.lifeengine.cryptobot.validator.policy.PolicyVerdict;
import io.lifeengine.cryptobot.validator.tx.CompiledTransaction;
import io.lifeengine.cryptobot.validator.tx.SwapRules;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The independent validation step of paper §20: {@code AI process → unsigned intent → Independent
 * Validator → Signer}. Given the facts {@code (I, S)} the agent claims it decided on, the hash of
 * the policy it claims it used and the hash of the exact transaction bytes it wants signed, this
 * service:
 *
 * <ol>
 *   <li>refuses if the claimed {@code H_R} is not the policy pinned in <em>this</em> process;
 *   <li>re-derives the verdict with its own table ({@link IndependentPolicyTable}) — unknown facts
 *       deny — and refuses if the agent's recorded verdict hash disagrees (§17: validator
 *       disagreement ⇒ DENY);
 *   <li>issues a short-lived Ed25519 <b>attestation</b> bound to the proposal, the transaction
 *       message hash, the cluster the bytes are for, {@code H_R}, the input hash and
 *       the verdict hash. The signer signs nothing without one, and nothing whose bytes — or
 *       cluster — are not the ones attested.
 * </ol>
 *
 * The attestation says "an independent process, holding policy {@code H_R}, re-derived this
 * verdict over these facts for these bytes, now". It does not say the facts are true: that is
 * the post-MVP step where the validator reads state from the chain itself.
 *
 * <p>KAN-600: the caller may send the unsigned transaction itself ({@code transaction}, base64). Then the validator
 * decodes it and computes {@code message_hash} from the bytes; a claimed hash that differs is refused
 * ({@code MESSAGE_HASH_MISMATCH}) and the attestation always carries the computed one, so the signer (which hashes
 * the bytes it is asked to sign) cannot be handed other bytes — another blockhash, another signer, another anything —
 * under this attestation. With a {@code swap} intent, {@link SwapRules} is applied to those same bytes and any
 * violation denies ({@code SWAP_RULES}). {@code checks} in the payload records what was actually verified, so the
 * signer can later require it, and {@code swap} carries the swap itself (mints, input amount, minimum output) so the
 * signer can require the exact minimum it signs for to be the attested one.
 */
@Component
public class ValidationService {

    private static final Logger log = LoggerFactory.getLogger(ValidationService.class);
    private static final Pattern HEX64 = Pattern.compile("[0-9a-f]{64}");
    public static final String SCHEMA_VERSION = "1";

    public static final String REFUSAL_DISABLED = "VALIDATOR_DISABLED";
    public static final String REFUSAL_POLICY_HASH = "POLICY_HASH_MISMATCH";
    public static final String REFUSAL_DISAGREEMENT = "VERDICT_DISAGREEMENT";
    public static final String REFUSAL_MESSAGE_HASH = "MESSAGE_HASH_MISMATCH";
    public static final String REFUSAL_SWAP_RULES = "SWAP_RULES";
    public static final String CHECK_HASH_FROM_BYTES = "message_hash_from_bytes";
    public static final String CHECK_JUPITER_SWAP = "jupiter_swap_v1";

    /** {@code cluster}: {@code devnet} | {@code mainnet-beta} ({@code mainnet} accepted); required, attested verbatim. */
    public record Request(
            String proposalId,
            String policyHash,
            String expectedVerdictHash,
            String messageHash,
            Map<String, Object> intent,
            Map<String, Object> state,
            String cluster,
            String transaction,
            Map<String, Object> swap) {

        /** The pre-KAN-600 shape: only the hash of the bytes. */
        public Request(String proposalId, String policyHash, String expectedVerdictHash, String messageHash, Map<String, Object> intent,
                Map<String, Object> state, String cluster) {
            this(proposalId, policyHash, expectedVerdictHash, messageHash, intent, state, cluster, null, null);
        }
    }

    public record Attestation(String payload, String signature, String validator) {}

    public record Response(
            String decision,
            String escalation,
            String tier,
            List<String> failedPredicates,
            List<String> refusals,
            String policyVersion,
            String policyHash,
            String inputHash,
            String verdictHash,
            long issuedAt,
            long expiresAt,
            Attestation attestation,
            String messageHash,
            List<String> checks,
            List<String> swapViolations) {}

    /** A request the validator cannot even evaluate: 400, no attestation. */
    public static class MalformedRequest extends RuntimeException {
        public MalformedRequest(String reason) {
            super(reason);
        }
    }

    private final ValidatorProperties props;
    private final PolicyStore policy;
    private final AttestationKeyStore keys;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public ValidationService(ValidatorProperties props, PolicyStore policy, AttestationKeyStore keys) {
        this(props, policy, keys, Clock.systemUTC());
    }

    ValidationService(ValidatorProperties props, PolicyStore policy, AttestationKeyStore keys, Clock clock) {
        this.props = props;
        this.policy = policy;
        this.keys = keys;
        this.clock = clock;
    }

    public Response validate(Request req) {
        if (req == null) {
            throw new MalformedRequest("missing_body");
        }
        String proposalId = blankToNull(req.proposalId());
        if (proposalId == null) {
            throw new MalformedRequest("missing_proposal_id");
        }
        String claimedHash = blankToNull(req.messageHash()) == null ? null : req.messageHash().trim().toLowerCase(Locale.ROOT);
        CompiledTransaction tx = decodeTransaction(req.transaction());
        if (tx == null && (claimedHash == null || !HEX64.matcher(claimedHash).matches())) {
            throw new MalformedRequest("missing_or_invalid_message_hash");
        }
        if (req.swap() != null && tx == null) {
            throw new MalformedRequest("swap_requires_transaction");
        }
        SwapRules.SwapIntent swapIntent = req.swap() == null ? null : swapIntent(req.swap());
        String messageHash = tx == null ? claimedHash : sha256Hex(tx.message());
        List<String> checks = new ArrayList<>();
        List<String> swapViolations = List.of();
        String claimedPolicyHash = blankToNull(req.policyHash());
        if (claimedPolicyHash == null) {
            throw new MalformedRequest("missing_policy_hash");
        }
        String cluster = canonicalCluster(req.cluster());
        if (cluster == null) {
            throw new MalformedRequest("missing_or_invalid_cluster");
        }

        PolicyInput input = Facts.input(req.intent(), req.state());
        PolicyVerdict verdict = IndependentPolicyTable.evaluate(policy.rules(), input);
        List<String> refusals = new ArrayList<>();
        if (!props.enabled()) {
            refusals.add(REFUSAL_DISABLED);
        }
        if (!policy.hash().equals(claimedPolicyHash)) {
            refusals.add(REFUSAL_POLICY_HASH);
        }
        String expected = blankToNull(req.expectedVerdictHash());
        if (expected != null && !expected.equals(verdict.hash())) {
            refusals.add(REFUSAL_DISAGREEMENT);
        }
        if (tx != null) {
            checks.add(CHECK_HASH_FROM_BYTES);
            if (claimedHash != null && !claimedHash.equals(messageHash)) {
                refusals.add(REFUSAL_MESSAGE_HASH);
            }
        }
        if (swapIntent != null) {
            checks.add(CHECK_JUPITER_SWAP);
            swapViolations = SwapRules.check(tx, swapIntent).violations().stream().map(v -> v.code() + ": " + v.detail()).toList();
            if (!swapViolations.isEmpty()) {
                refusals.add(REFUSAL_SWAP_RULES);
            }
        }
        PolicyVerdict.Decision decision = refusals.isEmpty() ? verdict.decision() : PolicyVerdict.Decision.DENY;
        PolicyVerdict.Escalation escalation = refusals.isEmpty() ? verdict.escalation() : PolicyVerdict.Escalation.NONE;

        Instant issued = clock.instant();
        Instant expires = issued.plus(props.attestationTtl());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schema_version", SCHEMA_VERSION);
        payload.put("proposal_id", proposalId);
        payload.put("message_hash", messageHash);
        payload.put("cluster", cluster);
        payload.put("policy_hash", policy.hash());
        payload.put("input_hash", verdict.inputHash());
        payload.put("verdict_hash", verdict.hash());
        payload.put("decision", decision.name());
        payload.put("escalation", escalation.name());
        payload.put("validator", keys.publicKey());
        payload.put("issued_at", issued.getEpochSecond());
        payload.put("expires_at", expires.getEpochSecond());
        if (!checks.isEmpty()) {
            payload.put("checks", List.copyOf(checks));
        }
        if (swapIntent != null) {
            // pilot: the swap the signer may sign, in the signed payload, so the exact minimum is authenticated end to end
            Map<String, Object> swap = new LinkedHashMap<>();
            swap.put("input_mint", swapIntent.inputMint());
            swap.put("output_mint", swapIntent.outputMint());
            swap.put("in_amount", swapIntent.inAmount());
            swap.put("min_out_amount", swapIntent.minOutAmount());
            payload.put("swap", swap);
        }
        String canonical = CanonicalJson.canonicalize(payload);
        String signature = Base58.encode(keys.sign(canonical.getBytes(StandardCharsets.UTF_8)));

        log.info("validator_decision proposalId={} cluster={} decision={} escalation={} tier={} failed={} refusals={} policyHash={} verdictHash={} messageHash={}",
                proposalId, cluster, decision, escalation, verdict.tier(), verdict.failedPredicates(), refusals, policy.hash(), verdict.hash(), messageHash,
                LogFields.event("validation_decided"), LogFields.status(decision.name().toLowerCase(java.util.Locale.ROOT)));
        return new Response(
                decision.name(),
                escalation.name(),
                verdict.tier().name(),
                verdict.failedPredicates().stream().map(Enum::name).toList(),
                List.copyOf(refusals),
                policy.rules().version(),
                policy.hash(),
                verdict.inputHash(),
                verdict.hash(),
                issued.getEpochSecond(),
                expires.getEpochSecond(),
                new Attestation(canonical, signature, keys.publicKey()),
                messageHash,
                List.copyOf(checks),
                swapViolations);
    }

    private static CompiledTransaction decodeTransaction(String base64) {
        String b = blankToNull(base64);
        if (b == null) {
            return null;
        }
        try {
            return CompiledTransaction.decode(Base64.getDecoder().decode(b));
        } catch (IllegalArgumentException | CompiledTransaction.MalformedTransaction ex) {
            throw new MalformedRequest("malformed_transaction");
        }
    }

    /** Wire swap intent → {@link SwapRules.SwapIntent}; every field is required and nothing is coerced. */
    private static SwapRules.SwapIntent swapIntent(Map<String, Object> s) {
        String owner = Facts.str(s.get("owner"));
        String input = Facts.str(s.get("input_mint"));
        String output = Facts.str(s.get("output_mint"));
        Long in = Facts.lng(s.get("in_amount"));
        Long minOut = Facts.lng(s.get("min_out_amount"));
        Integer slippage = Facts.integer(s.get("max_slippage_bps"));
        Long fee = Facts.lng(s.get("max_priority_fee_lamports"));
        if (owner == null || input == null || output == null || in == null || minOut == null || slippage == null || fee == null
                || !Base58.isPublicKey(owner) || !Base58.isPublicKey(input) || !Base58.isPublicKey(output)
                || in <= 0 || minOut <= 0 || slippage < 0 || slippage > 10_000 || fee < 0) {
            throw new MalformedRequest("invalid_swap_intent");
        }
        return new SwapRules.SwapIntent(owner, input, output, in, minOut, slippage, fee);
    }

    static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    /** {@code devnet} | {@code mainnet-beta}, or {@code null} for anything else — an unknown cluster is a malformed request. */
    static String canonicalCluster(String raw) {
        String v = blankToNull(raw);
        if (v == null) {
            return null;
        }
        return switch (v.toLowerCase(Locale.ROOT)) {
            case "devnet" -> "devnet";
            case "mainnet", "mainnet-beta" -> "mainnet-beta";
            default -> null;
        };
    }

    /**
     * Wire facts → {@link PolicyInput}. Keys are the schema's snake_case names; a missing key, a
     * wrong type, a non-integer number or an out-of-range integer is an unknown fact ({@code null})
     * — and an unknown fact fails its predicate. Nothing is coerced.
     */
    static final class Facts {

        private Facts() {}

        static PolicyInput input(Map<String, Object> intent, Map<String, Object> state) {
            Map<String, Object> i = intent == null ? Map.of() : intent;
            Map<String, Object> s = state == null ? Map.of() : state;
            return new PolicyInput(
                    new PolicyInput.IntentFacts(
                            str(i.get("agent_id")), str(i.get("strategy_id")), str(i.get("policy_version")), str(i.get("asset")),
                            lng(i.get("trade_value_cents")), integer(i.get("max_slippage_bps")), lng(i.get("valid_until_slot"))),
                    new PolicyInput.StateFacts(
                            lng(s.get("daily_exposure_cents")), integer(s.get("asset_exposure_after_bps")), lng(s.get("oracle_age_seconds")),
                            bool(s.get("agent_permitted")), bool(s.get("nonce_unused")), lng(s.get("current_slot"))));
        }

        static String str(Object v) {
            return v instanceof String s ? s : null;
        }

        static Boolean bool(Object v) {
            return v instanceof Boolean b ? b : null;
        }

        static Long lng(Object v) {
            if (v instanceof Integer n) {
                return n.longValue();
            }
            if (v instanceof Long n) {
                return n;
            }
            if (v instanceof Number n) {
                double d = n.doubleValue();
                return d == Math.rint(d) && Math.abs(d) <= CanonicalJson.MAX_SAFE_INTEGER ? (long) d : null;
            }
            return null;
        }

        static Integer integer(Object v) {
            Long l = lng(v);
            return l == null || l > Integer.MAX_VALUE || l < Integer.MIN_VALUE ? null : l.intValue();
        }
    }
}
