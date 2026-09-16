package io.lifeengine.cryptobot.domain.intent;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The boundary between <em>untrusted structured data</em> (what the LLM emitted, after the runtime
 * parsed it as JSON) and a <em>canonical intent</em> (paper §7). Everything here is fail-closed:
 *
 * <ul>
 *   <li>{@code schema_version} must be exactly {@value TradingIntent#SCHEMA_VERSION}.
 *   <li>Unknown fields are refused, not dropped: a field we do not understand may carry meaning we
 *       would be silently discarding from the hash.
 *   <li>Duplicate JSON keys are refused (a classic parser-differential attack).
 *   <li>Explicit {@code null} means <em>absent</em>: many serializers write nulls for empty
 *       optionals, and "field: null" must hash like "no field".
 *   <li>Integers are accepted as JSON integers or as strings of decimal digits (the amount in the
 *       paper travels as a string). Floats, exponents, signs, blanks and hex are refused, never
 *       rounded or "repaired".
 *   <li>{@code action} is matched case-insensitively against the finite vocabulary. Anything
 *       outside it is not an intent.
 * </ul>
 */
public final class IntentSchema {

    private static final ObjectMapper JSON = JsonMapper.builder()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            // "{…}{}" is two documents, not one: Jackson would read the first and drop the rest, and
            // another parser might not (KAN-440 benchmark, serialization attack "trailing garbage").
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .build();

    private static final TypeReference<LinkedHashMap<String, Object>> OBJECT = new TypeReference<>() {};

    private static final Set<String> KNOWN_FIELDS = Set.of(
            TradingIntent.F_SCHEMA_VERSION, TradingIntent.F_AGENT_ID, TradingIntent.F_ACTION,
            TradingIntent.F_STRATEGY_ID, TradingIntent.F_POLICY_VERSION, TradingIntent.F_VALID_UNTIL_SLOT,
            TradingIntent.F_NONCE, TradingIntent.F_INPUT_ASSET, TradingIntent.F_OUTPUT_ASSET,
            TradingIntent.F_INPUT_AMOUNT, TradingIntent.F_MAX_SLIPPAGE_BPS, TradingIntent.F_TARGET_INTENT_HASH,
            TradingIntent.F_TARGET_WEIGHTS_BPS, TradingIntent.F_COUNTER_ASSET);

    private static final Pattern DECIMAL_DIGITS = Pattern.compile("^(0|[1-9][0-9]*)$");

    private IntentSchema() {}

    /** Parses the JSON text an agent emitted. */
    public static TradingIntent parse(String json) {
        if (json == null || json.isBlank()) {
            throw new IntentSchemaViolation("$", "empty document");
        }
        Map<String, Object> tree;
        try {
            tree = JSON.readValue(json, OBJECT);
        } catch (IOException e) {
            throw new IntentSchemaViolation("$", "not a JSON object: " + firstLine(e.getMessage()));
        }
        return parse(tree);
    }

    /** Parses an already-decoded JSON object (e.g. a Jackson {@code Map}). */
    public static TradingIntent parse(Map<String, ?> untrusted) {
        if (untrusted == null) {
            throw new IntentSchemaViolation("$", "not a JSON object");
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        for (Map.Entry<String, ?> e : untrusted.entrySet()) {
            if (!KNOWN_FIELDS.contains(e.getKey())) {
                throw new IntentSchemaViolation(e.getKey(), "unknown field");
            }
            if (e.getValue() != null) {
                fields.put(e.getKey(), e.getValue());
            }
        }

        String version = string(fields, TradingIntent.F_SCHEMA_VERSION, true);
        if (!TradingIntent.SCHEMA_VERSION.equals(version)) {
            throw new IntentSchemaViolation(TradingIntent.F_SCHEMA_VERSION, "unsupported schema version " + version
                    + " (expected " + TradingIntent.SCHEMA_VERSION + ")");
        }

        IntentAction action = action(string(fields, TradingIntent.F_ACTION, true));
        String agentId = string(fields, TradingIntent.F_AGENT_ID, true);
        String strategyId = string(fields, TradingIntent.F_STRATEGY_ID, true);
        String policyVersion = string(fields, TradingIntent.F_POLICY_VERSION, true);
        long validUntilSlot = safeLong(fields, TradingIntent.F_VALID_UNTIL_SLOT, true);
        long nonce = safeLong(fields, TradingIntent.F_NONCE, true);

        AssetId inputAsset = asset(fields, TradingIntent.F_INPUT_ASSET);
        AssetId outputAsset = asset(fields, TradingIntent.F_OUTPUT_ASSET);
        BigInteger inputAmount = integer(fields, TradingIntent.F_INPUT_AMOUNT, false);
        Integer maxSlippageBps = bps(fields, TradingIntent.F_MAX_SLIPPAGE_BPS);
        IntentHash targetIntentHash = null;
        if (fields.containsKey(TradingIntent.F_TARGET_INTENT_HASH)) {
            try {
                targetIntentHash = IntentHash.parse(string(fields, TradingIntent.F_TARGET_INTENT_HASH, true));
            } catch (IntentSchemaViolation e) {
                throw new IntentSchemaViolation(TradingIntent.F_TARGET_INTENT_HASH, "must be sha256:<64 hex>");
            }
        }
        Map<AssetId, Integer> weights = weights(fields);
        AssetId counterAsset = asset(fields, TradingIntent.F_COUNTER_ASSET);

        return new TradingIntent(agentId, action, strategyId, policyVersion, validUntilSlot, nonce,
                inputAsset, outputAsset, inputAmount, maxSlippageBps, targetIntentHash, weights, counterAsset);
    }

    // ---- field decoders ---------------------------------------------------------------------

    private static IntentAction action(String raw) {
        String normalized = TradingIntent.text(TradingIntent.F_ACTION, raw).toUpperCase(Locale.ROOT);
        for (IntentAction a : IntentAction.values()) {
            if (a.name().equals(normalized)) {
                return a;
            }
        }
        throw new IntentSchemaViolation(TradingIntent.F_ACTION, "not in the vocabulary " + java.util.Arrays.toString(IntentAction.values()) + ": " + raw);
    }

    private static String string(Map<String, Object> fields, String field, boolean required) {
        Object v = fields.get(field);
        if (v == null) {
            if (required) {
                throw new IntentSchemaViolation(field, "missing");
            }
            return null;
        }
        if (v instanceof String s) {
            return s;
        }
        throw new IntentSchemaViolation(field, "must be a string, got " + typeName(v));
    }

    private static AssetId asset(Map<String, Object> fields, String field) {
        String raw = string(fields, field, false);
        if (raw == null) {
            return null;
        }
        try {
            return AssetId.of(raw);
        } catch (IntentSchemaViolation e) {
            throw new IntentSchemaViolation(field, e.getMessage());
        }
    }

    /** A JSON integer or a string of decimal digits; anything else (float, sign, exponent, hex) is refused. */
    private static BigInteger integer(Map<String, Object> fields, String field, boolean required) {
        Object v = fields.get(field);
        if (v == null) {
            if (required) {
                throw new IntentSchemaViolation(field, "missing");
            }
            return null;
        }
        if (v instanceof BigInteger b) {
            return b;
        }
        if (v instanceof Integer || v instanceof Long || v instanceof Short || v instanceof Byte) {
            return BigInteger.valueOf(((Number) v).longValue());
        }
        if (v instanceof BigDecimal || v instanceof Double || v instanceof Float) {
            // A JSON float token — even "250000000.0" — is not how an integer is written. Refused,
            // not rounded: the agent must emit an integer or a string of digits.
            throw new IntentSchemaViolation(field, "floating-point is not an integer: " + v);
        }
        if (v instanceof String s) {
            String t = TradingIntent.text(field, s);
            if (!DECIMAL_DIGITS.matcher(t).matches()) {
                throw new IntentSchemaViolation(field, "must be an integer or a string of decimal digits, got \"" + s + "\"");
            }
            return new BigInteger(t);
        }
        throw new IntentSchemaViolation(field, "must be an integer, got " + typeName(v));
    }

    private static long safeLong(Map<String, Object> fields, String field, boolean required) {
        BigInteger b = integer(fields, field, required);
        if (b == null) {
            return 0L;
        }
        if (b.abs().compareTo(BigInteger.valueOf(JsonCanonicalizer.MAX_SAFE_INTEGER)) > 0) {
            throw new IntentSchemaViolation(field, "outside the safe integer range: " + b);
        }
        return b.longValue();
    }

    /** A basis-point value: an integer within {@code 0..10000}; {@code null} when absent. */
    private static Integer bps(Map<String, Object> fields, String field) {
        BigInteger b = integer(fields, field, false);
        if (b == null) {
            return null;
        }
        if (b.signum() < 0 || b.compareTo(BigInteger.valueOf(TradingIntent.MAX_BPS)) > 0) {
            throw new IntentSchemaViolation(field, "must be within 0.." + TradingIntent.MAX_BPS + ", got " + b);
        }
        return b.intValue();
    }

    private static Map<AssetId, Integer> weights(Map<String, Object> fields) {
        Object v = fields.get(TradingIntent.F_TARGET_WEIGHTS_BPS);
        if (v == null) {
            return null;
        }
        if (!(v instanceof Map<?, ?> raw)) {
            throw new IntentSchemaViolation(TradingIntent.F_TARGET_WEIGHTS_BPS, "must be an object asset → bps, got " + typeName(v));
        }
        Map<AssetId, Integer> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : raw.entrySet()) {
            String path = TradingIntent.F_TARGET_WEIGHTS_BPS + "." + e.getKey();
            if (!(e.getKey() instanceof String key)) {
                throw new IntentSchemaViolation(TradingIntent.F_TARGET_WEIGHTS_BPS, "keys must be asset identifiers");
            }
            AssetId asset;
            try {
                asset = AssetId.of(key);
            } catch (IntentSchemaViolation ex) {
                throw new IntentSchemaViolation(path, ex.getMessage());
            }
            if (e.getValue() == null) {
                throw new IntentSchemaViolation(path, "missing");
            }
            Integer bps = bps(Map.of(path, e.getValue()), path);
            if (out.put(asset, bps) != null) {
                throw new IntentSchemaViolation(path, "asset listed twice after normalization");
            }
        }
        return out;
    }

    private static String typeName(Object v) {
        if (v instanceof Map) {
            return "object";
        }
        if (v instanceof java.util.Collection) {
            return "array";
        }
        if (v instanceof Number) {
            return "number";
        }
        if (v instanceof Boolean) {
            return "boolean";
        }
        return v.getClass().getSimpleName();
    }

    private static String firstLine(String s) {
        if (s == null) {
            return "";
        }
        int nl = s.indexOf('\n');
        return nl < 0 ? s : s.substring(0, nl);
    }
}
