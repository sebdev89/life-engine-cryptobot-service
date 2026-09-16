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
