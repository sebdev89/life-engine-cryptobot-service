package io.lifeengine.cryptobot.core.intent;

import java.math.BigInteger;
import java.text.Normalizer;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * The structured intent an agent emits (paper §6). The LLM does not execute: it proposes one of
 * these, the schema refuses anything that does not fit, the canonical form is hashed, and that hash
 * ({@link #hash()}) is the identity of the proposal through validation, execution and receipts.
 *
 * <p>Schema version {@value #SCHEMA_VERSION}. Field matrix, by action:
 *
 * <pre>
 * always          : schema_version, agent_id, action, strategy_id, policy_version, valid_until_slot, nonce
 * BUY SELL SWAP   : + input_asset, output_asset, input_amount (string, minimal units), max_slippage_bps
 * REBALANCE       : + target_weights_bps (asset → bps, sum 10000), counter_asset, max_slippage_bps
 * CANCEL          : + target_intent_hash
 * HOLD            : nothing else
 * </pre>
 *
 * A field that the action does not use is <em>forbidden</em>, not ignored: an intent that carries
 * an amount and says HOLD is ambiguous, and ambiguity is refused (paper §17, fail-closed).
 *
 * <p>Canonical types: identifiers are NFC strings; {@code input_amount} is a decimal string (an
 * SPL amount is a u64 and does not fit a JSON number); {@code max_slippage_bps},
 * {@code valid_until_slot}, {@code nonce} and every weight are JSON integers within the safe range.
 */
public record TradingIntent(
        String agentId,
        IntentAction action,
        String strategyId,
        String policyVersion,
        long validUntilSlot,
        long nonce,
        AssetId inputAsset,
        AssetId outputAsset,
        BigInteger inputAmount,
        Integer maxSlippageBps,
        IntentHash targetIntentHash,
        Map<AssetId, Integer> targetWeightsBps,
        AssetId counterAsset) {

    public static final String SCHEMA_VERSION = "1";

    public static final String F_SCHEMA_VERSION = "schema_version";
    public static final String F_AGENT_ID = "agent_id";
    public static final String F_ACTION = "action";
    public static final String F_STRATEGY_ID = "strategy_id";
    public static final String F_POLICY_VERSION = "policy_version";
    public static final String F_VALID_UNTIL_SLOT = "valid_until_slot";
    public static final String F_NONCE = "nonce";
    public static final String F_INPUT_ASSET = "input_asset";
    public static final String F_OUTPUT_ASSET = "output_asset";
    public static final String F_INPUT_AMOUNT = "input_amount";
    public static final String F_MAX_SLIPPAGE_BPS = "max_slippage_bps";
    public static final String F_TARGET_INTENT_HASH = "target_intent_hash";
    public static final String F_TARGET_WEIGHTS_BPS = "target_weights_bps";
    public static final String F_COUNTER_ASSET = "counter_asset";

    /** Largest SPL token amount: u64. */
    public static final BigInteger MAX_AMOUNT = BigInteger.TWO.pow(64).subtract(BigInteger.ONE);
    public static final int MAX_BPS = 10_000;
    public static final int MAX_IDENTIFIER_LENGTH = 128;

    public TradingIntent {
        agentId = identifier(F_AGENT_ID, agentId);
        strategyId = identifier(F_STRATEGY_ID, strategyId);
        policyVersion = identifier(F_POLICY_VERSION, policyVersion);
        if (action == null) {
            throw new IntentSchemaViolation(F_ACTION, "missing");
        }
        if (validUntilSlot <= 0 || validUntilSlot > JsonCanonicalizer.MAX_SAFE_INTEGER) {
            throw new IntentSchemaViolation(F_VALID_UNTIL_SLOT, "must be a positive slot within the safe integer range, got " + validUntilSlot);
        }
        if (nonce < 0 || nonce > JsonCanonicalizer.MAX_SAFE_INTEGER) {
            throw new IntentSchemaViolation(F_NONCE, "must be a non-negative integer within the safe integer range, got " + nonce);
        }
        targetWeightsBps = targetWeightsBps == null ? null : Map.copyOf(targetWeightsBps);

        switch (action) {
            case BUY, SELL, SWAP -> {
                require(F_INPUT_ASSET, inputAsset);
                require(F_OUTPUT_ASSET, outputAsset);
                require(F_INPUT_AMOUNT, inputAmount);
                require(F_MAX_SLIPPAGE_BPS, maxSlippageBps);
                forbid(action, F_TARGET_INTENT_HASH, targetIntentHash);
                forbid(action, F_TARGET_WEIGHTS_BPS, targetWeightsBps);
                forbid(action, F_COUNTER_ASSET, counterAsset);
                if (inputAsset.equals(outputAsset)) {
                    throw new IntentSchemaViolation(F_OUTPUT_ASSET, "must differ from input_asset (" + inputAsset + ")");
                }
                checkAmount(inputAmount);
                checkBps(F_MAX_SLIPPAGE_BPS, maxSlippageBps);
            }
            case REBALANCE -> {
                require(F_TARGET_WEIGHTS_BPS, targetWeightsBps);
                require(F_COUNTER_ASSET, counterAsset);
                require(F_MAX_SLIPPAGE_BPS, maxSlippageBps);
                forbid(action, F_INPUT_ASSET, inputAsset);
                forbid(action, F_OUTPUT_ASSET, outputAsset);
                forbid(action, F_INPUT_AMOUNT, inputAmount);
                forbid(action, F_TARGET_INTENT_HASH, targetIntentHash);
                checkWeights(targetWeightsBps);
                checkBps(F_MAX_SLIPPAGE_BPS, maxSlippageBps);
            }
            case CANCEL -> {
                require(F_TARGET_INTENT_HASH, targetIntentHash);
                forbid(action, F_INPUT_ASSET, inputAsset);
                forbid(action, F_OUTPUT_ASSET, outputAsset);
                forbid(action, F_INPUT_AMOUNT, inputAmount);
                forbid(action, F_MAX_SLIPPAGE_BPS, maxSlippageBps);
                forbid(action, F_TARGET_WEIGHTS_BPS, targetWeightsBps);
                forbid(action, F_COUNTER_ASSET, counterAsset);
            }
            case HOLD -> {
                forbid(action, F_INPUT_ASSET, inputAsset);
                forbid(action, F_OUTPUT_ASSET, outputAsset);
                forbid(action, F_INPUT_AMOUNT, inputAmount);
                forbid(action, F_MAX_SLIPPAGE_BPS, maxSlippageBps);
                forbid(action, F_TARGET_INTENT_HASH, targetIntentHash);
                forbid(action, F_TARGET_WEIGHTS_BPS, targetWeightsBps);
                forbid(action, F_COUNTER_ASSET, counterAsset);
            }
        }
    }

    // ---- factories -------------------------------------------------------------------------

    public static TradingIntent trade(IntentAction action, String agentId, String strategyId, String policyVersion,
            long validUntilSlot, long nonce, AssetId inputAsset, AssetId outputAsset, BigInteger inputAmount, int maxSlippageBps) {
        return new TradingIntent(agentId, action, strategyId, policyVersion, validUntilSlot, nonce,
                inputAsset, outputAsset, inputAmount, maxSlippageBps, null, null, null);
    }

    public static TradingIntent hold(String agentId, String strategyId, String policyVersion, long validUntilSlot, long nonce) {
        return new TradingIntent(agentId, IntentAction.HOLD, strategyId, policyVersion, validUntilSlot, nonce,
                null, null, null, null, null, null, null);
    }

    public static TradingIntent cancel(String agentId, String strategyId, String policyVersion, long validUntilSlot, long nonce,
            IntentHash target) {
        return new TradingIntent(agentId, IntentAction.CANCEL, strategyId, policyVersion, validUntilSlot, nonce,
                null, null, null, null, target, null, null);
    }

    public static TradingIntent rebalance(String agentId, String strategyId, String policyVersion, long validUntilSlot, long nonce,
            Map<AssetId, Integer> targetWeightsBps, AssetId counterAsset, int maxSlippageBps) {
        return new TradingIntent(agentId, IntentAction.REBALANCE, strategyId, policyVersion, validUntilSlot, nonce,
                null, null, null, maxSlippageBps, null, targetWeightsBps, counterAsset);
    }

    // ---- canonical form ---------------------------------------------------------------------

    /**
     * The value tree that gets canonicalized: every present field with its canonical type, absent
     * fields omitted. Insertion order is irrelevant — {@link JsonCanonicalizer} sorts.
     */
    public Map<String, Object> canonicalMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(F_SCHEMA_VERSION, SCHEMA_VERSION);
        m.put(F_AGENT_ID, agentId);
        m.put(F_ACTION, action.name());
        m.put(F_STRATEGY_ID, strategyId);
        m.put(F_POLICY_VERSION, policyVersion);
        m.put(F_VALID_UNTIL_SLOT, validUntilSlot);
        m.put(F_NONCE, nonce);
        if (inputAsset != null) {
            m.put(F_INPUT_ASSET, inputAsset.value());
        }
        if (outputAsset != null) {
            m.put(F_OUTPUT_ASSET, outputAsset.value());
        }
        if (inputAmount != null) {
            m.put(F_INPUT_AMOUNT, inputAmount.toString());
        }
        if (maxSlippageBps != null) {
            m.put(F_MAX_SLIPPAGE_BPS, maxSlippageBps);
        }
        if (targetIntentHash != null) {
            m.put(F_TARGET_INTENT_HASH, targetIntentHash.value());
        }
        if (targetWeightsBps != null) {
            Map<String, Object> weights = new TreeMap<>();
            targetWeightsBps.forEach((asset, bps) -> weights.put(asset.value(), bps));
            m.put(F_TARGET_WEIGHTS_BPS, weights);
        }
        if (counterAsset != null) {
            m.put(F_COUNTER_ASSET, counterAsset.value());
        }
        return m;
    }

    /** {@code C = Canonicalize(I)}: the RFC 8785 text of {@link #canonicalMap()}. */
    public String canonicalJson() {
        return JsonCanonicalizer.canonicalize(canonicalMap());
    }

    /** {@code H_I = SHA-256(C)}: the identity of this intent in the whole pipeline. */
    public IntentHash hash() {
        return IntentHash.of(JsonCanonicalizer.canonicalBytes(canonicalMap()));
    }

    // ---- validation helpers -----------------------------------------------------------------

    private static String identifier(String field, String raw) {
        String s = text(field, raw);
        if (s.isEmpty()) {
            throw new IntentSchemaViolation(field, "must not be blank");
        }
        if (s.length() > MAX_IDENTIFIER_LENGTH) {
            throw new IntentSchemaViolation(field, "longer than " + MAX_IDENTIFIER_LENGTH + " characters");
        }
        return s;
    }

    /**
     * Untrusted text → canonical text: NFC, then surrounding spaces removed. Control characters
     * are refused <em>before</em> trimming: {@code String.trim()} silently drops every code point
     * below U+0021, so {@code "paper-v1"} followed by U+0000 used to normalize to {@code "paper-v1"} and bind to a
     * policy the agent never named (found by the KAN-440 benchmark). Two byte strings that differ
     * must not become one identifier unless the difference is plain spaces.
     */
    static String text(String field, String raw) {
        if (raw == null) {
            throw new IntentSchemaViolation(field, "missing");
        }
        String s = Normalizer.normalize(raw, Normalizer.Form.NFC);
        for (int i = 0; i < s.length(); i++) {
            if (Character.isISOControl(s.charAt(i))) {
                throw new IntentSchemaViolation(field, "must not contain control characters");
            }
        }
        return s.trim();
    }

    private static void require(String field, Object value) {
        if (value == null) {
            throw new IntentSchemaViolation(field, "missing");
        }
    }

    private static void forbid(IntentAction action, String field, Object value) {
        if (value != null) {
            throw new IntentSchemaViolation(field, "not allowed for " + action);
        }
    }

    private static void checkAmount(BigInteger amount) {
        if (amount.signum() <= 0) {
            throw new IntentSchemaViolation(F_INPUT_AMOUNT, "must be a positive integer in minimal units, got " + amount);
        }
        if (amount.compareTo(MAX_AMOUNT) > 0) {
            throw new IntentSchemaViolation(F_INPUT_AMOUNT, "exceeds u64: " + amount);
        }
    }

    private static void checkBps(String field, int bps) {
        if (bps < 0 || bps > MAX_BPS) {
            throw new IntentSchemaViolation(field, "must be within 0.." + MAX_BPS + ", got " + bps);
        }
    }

    private static void checkWeights(Map<AssetId, Integer> weights) {
        if (weights.isEmpty()) {
            throw new IntentSchemaViolation(F_TARGET_WEIGHTS_BPS, "must not be empty");
        }
        long sum = 0;
        for (Map.Entry<AssetId, Integer> e : weights.entrySet()) {
            if (e.getValue() == null) {
                throw new IntentSchemaViolation(F_TARGET_WEIGHTS_BPS + "." + e.getKey(), "missing");
            }
            checkBps(F_TARGET_WEIGHTS_BPS + "." + e.getKey(), e.getValue());
            sum += e.getValue();
        }
        if (sum != MAX_BPS) {
            throw new IntentSchemaViolation(F_TARGET_WEIGHTS_BPS, "weights must sum to " + MAX_BPS + " bps, got " + sum);
        }
    }
}
