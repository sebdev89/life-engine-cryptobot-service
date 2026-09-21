package io.lifeengine.cryptobot.domain.policy;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code (I, S)}: what the deterministic policy sees (paper §8). {@link IntentFacts} is what the
 * agent asked for; {@link StateFacts} is what the authoritative state says at evaluation time.
 * The policy never looks at anything else — not the prompt, not the reasoning, not the model.
 *
 * <p>Every field is boxed and may be {@code null}: <b>null means unknown</b>, and an unknown
 * fact fails its predicate (§17, {@code Unknown ⇒ Deny}). Nothing here is ever defaulted.
 *
 * <p>Units: cents (USD, 2 decimals), basis points, whole seconds, and a monotonic "slot" for
 * expiry — the intent and the state must use the same tick (a Solana slot, or the epoch second
 * until intents carry slots).
 */
public record PolicyInput(IntentFacts intent, StateFacts state) {

    public PolicyInput {
        intent = intent == null ? new IntentFacts(null, null, null, null, null, null, null) : intent;
        state = state == null ? new StateFacts(null, null, null, null, null, null) : state;
    }

    /** {@code I}: the intent, reduced to the facts the predicates need. */
    public record IntentFacts(
            String agentId,
            String strategyId,
            String policyVersion,
            String asset,
            Long tradeValueCents,
            Integer maxSlippageBps,
            Long validUntilSlot) {}

    /** {@code S}: the authoritative state at evaluation time, resolved by the caller. */
    public record StateFacts(
            Long dailyExposureCents,
            Integer assetExposureAfterBps,
            Long oracleAgeSeconds,
            Boolean agentPermitted,
            Boolean nonceUnused,
            Long currentSlot) {}

    /** Known facts only; an absent key is an unknown fact, and the hash says so. */
    public Map<String, Object> canonicalMap() {
        Map<String, Object> i = new LinkedHashMap<>();
        put(i, "agent_id", intent.agentId());
        put(i, "strategy_id", intent.strategyId());
        put(i, "policy_version", intent.policyVersion());
        put(i, "asset", intent.asset());
        put(i, "trade_value_cents", intent.tradeValueCents());
        put(i, "max_slippage_bps", intent.maxSlippageBps());
        put(i, "valid_until_slot", intent.validUntilSlot());
        Map<String, Object> s = new LinkedHashMap<>();
        put(s, "daily_exposure_cents", state.dailyExposureCents());
        put(s, "asset_exposure_after_bps", state.assetExposureAfterBps());
        put(s, "oracle_age_seconds", state.oracleAgeSeconds());
        put(s, "agent_permitted", state.agentPermitted());
        put(s, "nonce_unused", state.nonceUnused());
        put(s, "current_slot", state.currentSlot());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("schema_version", PolicyRules.SCHEMA_VERSION);
        m.put("intent", i);
        m.put("state", s);
        return m;
    }

    public String canonicalJson() {
        return CanonicalJson.canonicalize(canonicalMap());
    }

    /**
     * Inverse of {@link #canonicalMap()} (KAN-572): the {@code (I, S)} a Decision Receipt stored
     * next to itself, read back so {@code verify} can re-run the engine. An absent key is an unknown
     * fact, exactly as it was when the verdict was computed; a value of the wrong shape is invalid.
     */
    @SuppressWarnings("unchecked")
    public static PolicyInput fromMap(Map<String, Object> m) {
        if (m == null) {
            throw new IllegalArgumentException("policy input: missing");
        }
        if (!PolicyRules.SCHEMA_VERSION.equals(String.valueOf(m.get("schema_version")))) {
            throw new IllegalArgumentException("policy input: unsupported schema_version " + m.get("schema_version"));
        }
        Map<String, Object> i = (Map<String, Object>) m.getOrDefault("intent", Map.of());
        Map<String, Object> s = (Map<String, Object>) m.getOrDefault("state", Map.of());
        IntentFacts intent = new IntentFacts(
                text(i, "agent_id"),
                text(i, "strategy_id"),
                text(i, "policy_version"),
                text(i, "asset"),
                integer(i, "trade_value_cents"),
                intOrNull(i, "max_slippage_bps"),
                integer(i, "valid_until_slot"));
        StateFacts state = new StateFacts(
                integer(s, "daily_exposure_cents"),
                intOrNull(s, "asset_exposure_after_bps"),
                integer(s, "oracle_age_seconds"),
                bool(s, "agent_permitted"),
                bool(s, "nonce_unused"),
                integer(s, "current_slot"));
        return new PolicyInput(intent, state);
    }

    private static String text(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (v == null) {
            return null;
        }
        if (!(v instanceof String s)) {
            throw new IllegalArgumentException("policy input: " + key + " must be a string");
        }
        return s;
    }

    private static Long integer(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (v == null) {
            return null;
        }
        if (v instanceof Integer || v instanceof Long || v instanceof Short || v instanceof Byte) {
            return ((Number) v).longValue();
        }
        if (v instanceof java.math.BigInteger b) {
            return b.longValueExact();
        }
        throw new IllegalArgumentException("policy input: " + key + " must be an integer");
    }

    private static Integer intOrNull(Map<String, Object> m, String key) {
        Long v = integer(m, key);
        if (v == null) {
            return null;
        }
        if (v < Integer.MIN_VALUE || v > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("policy input: " + key + " out of range");
        }
        return v.intValue();
    }

    private static Boolean bool(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (v == null) {
            return null;
        }
        if (!(v instanceof Boolean b)) {
            throw new IllegalArgumentException("policy input: " + key + " must be a boolean");
        }
        return b;
    }

    /** {@code SHA-256(canonicalJson())}: identifies exactly which facts the verdict was about. */
    public String hash() {
        return CanonicalJson.sha256(canonicalJson());
    }

    /**
     * Absent when unknown. An integer outside the safe range cannot be written unambiguously
     * (RFC 8785 numbers are IEEE doubles), so it is treated as unknown too — which is also how
     * the predicates treat it.
     */
    private static void put(Map<String, Object> m, String key, Object value) {
        if (value == null) {
            return;
        }
        if (value instanceof Long l && (l > CanonicalJson.MAX_SAFE_INTEGER || l < -CanonicalJson.MAX_SAFE_INTEGER)) {
            return;
        }
        m.put(key, value);
    }
}
