package io.lifeengine.cryptobot.core.policy;

import java.text.Normalizer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

/**
 * {@code R_v}: one version of the deterministic policy (paper §8, §11, §18). Every value is an
 * integer and every limit is enforced in code — a prompt saying "never trade more than X" is
 * guidance; {@code trade_value_cents <= max_trade_value_cents} is authority.
 *
 * <p>Money is in USD cents ({@code long}), ratios in basis points ({@code int}, 0..10000), time
 * in whole seconds. The canonical JSON of this record is the policy text; {@link #hash()} is
 * {@code H_R = SHA-256(R_v)}, committed in every verdict and later in every receipt, so a decision
 * can always be re-checked against the exact rules that produced it.
 *
 * <p>Tiers by trade value (§18): {@code ≤ autonomous_up_to} → ALLOW · {@code ≤ second_agent_up_to}
 * → ESCALATE(REQUIRE_SECOND_AGENT) · {@code ≤ max_trade_value} → ESCALATE(REQUIRE_HUMAN_SIGNATURE)
 * · above → DENY. The constructor refuses a policy whose tiers are not monotonic: a policy that
 * cannot be loaded must not exist (§17).
 */
public record PolicyRules(
        String version,
        List<String> allowedAssets,
        List<String> enabledStrategies,
        long maxTradeValueCents,
        long dailyLimitCents,
        int maxAssetExposureBps,
        int maxSlippageBps,
        long maxOracleAgeSeconds,
        long autonomousUpToCents,
        long secondAgentUpToCents) {

    public static final String SCHEMA_VERSION = "1";
    public static final int MAX_BPS = 10_000;

    public PolicyRules {
        version = identifier("version", version);
        allowedAssets = normalizedSet("allowed_assets", allowedAssets, true);
        enabledStrategies = normalizedSet("enabled_strategies", enabledStrategies, false);
        nonNegative("max_trade_value_cents", maxTradeValueCents);
        nonNegative("daily_limit_cents", dailyLimitCents);
        bps("max_asset_exposure_bps", maxAssetExposureBps);
        bps("max_slippage_bps", maxSlippageBps);
        nonNegative("max_oracle_age_seconds", maxOracleAgeSeconds);
        nonNegative("autonomous_up_to_cents", autonomousUpToCents);
        nonNegative("second_agent_up_to_cents", secondAgentUpToCents);
        if (autonomousUpToCents > secondAgentUpToCents) {
            throw new IllegalArgumentException("autonomous_up_to_cents (" + autonomousUpToCents + ") exceeds second_agent_up_to_cents (" + secondAgentUpToCents + ")");
        }
        if (secondAgentUpToCents > maxTradeValueCents) {
            throw new IllegalArgumentException("second_agent_up_to_cents (" + secondAgentUpToCents + ") exceeds max_trade_value_cents (" + maxTradeValueCents + ")");
        }
    }

    /** The value tree that is hashed. Field order is irrelevant: the canonicalizer sorts. */
    public Map<String, Object> canonicalMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("schema_version", SCHEMA_VERSION);
        m.put("version", version);
        m.put("allowed_assets", allowedAssets);
        m.put("enabled_strategies", enabledStrategies);
        m.put("max_trade_value_cents", maxTradeValueCents);
        m.put("daily_limit_cents", dailyLimitCents);
        m.put("max_asset_exposure_bps", maxAssetExposureBps);
        m.put("max_slippage_bps", maxSlippageBps);
        m.put("max_oracle_age_seconds", maxOracleAgeSeconds);
        m.put("autonomous_up_to_cents", autonomousUpToCents);
        m.put("second_agent_up_to_cents", secondAgentUpToCents);
        return m;
    }

    /** RFC 8785 text of {@link #canonicalMap()}: the policy as it is committed. */
    public String canonicalJson() {
        return CanonicalJson.canonicalize(canonicalMap());
    }

    /** {@code H_R = SHA-256(canonicalJson())}, as {@code sha256:<hex>}. */
    public String hash() {
        return CanonicalJson.sha256(canonicalJson());
    }

    public boolean allowsAsset(String asset) {
        return asset != null && allowedAssets.contains(asset.trim().toUpperCase(Locale.ROOT));
    }

    public boolean enablesStrategy(String strategy) {
        return strategy != null && enabledStrategies.contains(Normalizer.normalize(strategy.trim(), Normalizer.Form.NFC));
    }

    // ---- validation -----------------------------------------------------------------------

    static String identifier(String field, String raw) {
        if (raw == null) {
            throw new IllegalArgumentException(field + ": missing");
        }
        String s = Normalizer.normalize(raw.trim(), Normalizer.Form.NFC);
        if (s.isEmpty()) {
            throw new IllegalArgumentException(field + ": must not be blank");
        }
        if (s.length() > 128) {
            throw new IllegalArgumentException(field + ": longer than 128 characters");
        }
        for (int i = 0; i < s.length(); i++) {
            if (Character.isISOControl(s.charAt(i))) {
                throw new IllegalArgumentException(field + ": must not contain control characters");
            }
        }
        return s;
    }

    /** Same normalization as {@link #identifier} but a bad value is {@code null} (unknown), not an error. */
    static String identifierOrNull(String raw) {
        try {
            return identifier("value", raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Sorted, de-duplicated, NFC; assets upper-cased. An empty set is legal and allows nothing. */
    private static List<String> normalizedSet(String field, List<String> raw, boolean upperCase) {
        TreeSet<String> set = new TreeSet<>();
        if (raw != null) {
            for (String s : raw) {
                String id = identifier(field, s);
                set.add(upperCase ? id.toUpperCase(Locale.ROOT) : id);
            }
        }
        return List.copyOf(set);
    }

    private static void nonNegative(String field, long v) {
        if (v < 0 || v > CanonicalJson.MAX_SAFE_INTEGER) {
            throw new IllegalArgumentException(field + ": must be within 0.." + CanonicalJson.MAX_SAFE_INTEGER + ", got " + v);
        }
    }

    private static void bps(String field, int v) {
        if (v < 0 || v > MAX_BPS) {
            throw new IllegalArgumentException(field + ": must be within 0.." + MAX_BPS + " bps, got " + v);
        }
    }
}
