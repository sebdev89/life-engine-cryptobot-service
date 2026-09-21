package io.lifeengine.cryptobot.domain.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

public class PolicyRulesTest {

    public static PolicyRules paper() {
        return new PolicyRules("paper-v1", List.of("USDC", "SOL"), List.of("momentum-v3", "REBALANCE"),
                5_000_000L, 10_000_000L, 6_000, 50, 60L, 100_000L, 1_000_000L);
    }

    @Test
    void canonicalFormSortsKeysAndSetsAndIsIndependentOfInputOrder() {
        PolicyRules a = paper();
        PolicyRules b = new PolicyRules("paper-v1", List.of("sol", "USDC", "SOL"), List.of("REBALANCE", "momentum-v3"),
                5_000_000L, 10_000_000L, 6_000, 50, 60L, 100_000L, 1_000_000L);
        assertThat(a.canonicalJson()).isEqualTo(
                "{\"allowed_assets\":[\"SOL\",\"USDC\"],\"autonomous_up_to_cents\":100000,\"daily_limit_cents\":10000000,"
                + "\"enabled_strategies\":[\"REBALANCE\",\"momentum-v3\"],\"max_asset_exposure_bps\":6000,\"max_oracle_age_seconds\":60,"
                + "\"max_slippage_bps\":50,\"max_trade_value_cents\":5000000,\"schema_version\":\"1\",\"second_agent_up_to_cents\":1000000,"
                + "\"version\":\"paper-v1\"}");
        assertThat(b.canonicalJson()).isEqualTo(a.canonicalJson());
        assertThat(b.hash()).isEqualTo(a.hash()).matches("sha256:[0-9a-f]{64}");
    }

    @Test
    void anyValueChangeChangesTheHash() {
        String base = paper().hash();
        assertThat(new PolicyRules("paper-v2", List.of("USDC", "SOL"), List.of("momentum-v3", "REBALANCE"),
                5_000_000L, 10_000_000L, 6_000, 50, 60L, 100_000L, 1_000_000L).hash()).isNotEqualTo(base);
        assertThat(new PolicyRules("paper-v1", List.of("USDC", "SOL"), List.of("momentum-v3", "REBALANCE"),
                5_000_001L, 10_000_000L, 6_000, 50, 60L, 100_000L, 1_000_000L).hash()).isNotEqualTo(base);
        assertThat(new PolicyRules("paper-v1", List.of("USDC", "SOL", "JUP"), List.of("momentum-v3", "REBALANCE"),
                5_000_000L, 10_000_000L, 6_000, 50, 60L, 100_000L, 1_000_000L).hash()).isNotEqualTo(base);
    }

    @Test
    void identifiersAreNfcNormalizedTrimmedAndBounded() {
        // "é" as e + combining acute (NFD) and as the precomposed code point (NFC) are the same policy
        PolicyRules nfd = new PolicyRules(" polética ", List.of("SOL"), List.of(), 1, 1, 0, 0, 0, 0, 0);
        PolicyRules nfc = new PolicyRules("polética", List.of("SOL"), List.of(), 1, 1, 0, 0, 0, 0, 0);
        assertThat(nfd.version()).isEqualTo("polética");
        assertThat(nfd.hash()).isEqualTo(nfc.hash());
        assertThatThrownBy(() -> new PolicyRules("", List.of(), List.of(), 1, 1, 0, 0, 0, 0, 0)).hasMessageContaining("version");
        assertThatThrownBy(() -> new PolicyRules("a\nb", List.of(), List.of(), 1, 1, 0, 0, 0, 0, 0)).hasMessageContaining("control");
        assertThatThrownBy(() -> new PolicyRules("v", List.of(" "), List.of(), 1, 1, 0, 0, 0, 0, 0)).hasMessageContaining("allowed_assets");
    }

    @Test
    void tiersMustBeMonotonicAndValuesInRange() {
        assertThatThrownBy(() -> new PolicyRules("v", List.of(), List.of(), 100, 1, 0, 0, 0, 50, 40)).hasMessageContaining("autonomous_up_to_cents");
        assertThatThrownBy(() -> new PolicyRules("v", List.of(), List.of(), 100, 1, 0, 0, 0, 50, 101)).hasMessageContaining("second_agent_up_to_cents");
        assertThatThrownBy(() -> new PolicyRules("v", List.of(), List.of(), -1, 1, 0, 0, 0, 0, 0)).hasMessageContaining("max_trade_value_cents");
        assertThatThrownBy(() -> new PolicyRules("v", List.of(), List.of(), 1, 1, 10_001, 0, 0, 0, 0)).hasMessageContaining("max_asset_exposure_bps");
        assertThatThrownBy(() -> new PolicyRules("v", List.of(), List.of(), 1, 1, 0, -1, 0, 0, 0)).hasMessageContaining("max_slippage_bps");
        assertThatThrownBy(() -> new PolicyRules("v", List.of(), List.of(), 1, 1, 0, 0, CanonicalJson.MAX_SAFE_INTEGER + 1, 0, 0)).hasMessageContaining("max_oracle_age_seconds");
        // equal tiers are legal (a band can be empty)
        assertThat(new PolicyRules("v", List.of(), List.of(), 100, 1, 0, 0, 0, 100, 100).hash()).startsWith("sha256:");
    }

    @Test
    void emptyAllowlistsAllowNothing() {
        PolicyRules none = new PolicyRules("v", List.of(), List.of(), 100, 100, 0, 0, 0, 0, 0);
        assertThat(none.allowsAsset("SOL")).isFalse();
        assertThat(none.enablesStrategy("REBALANCE")).isFalse();
        assertThat(paper().allowsAsset("sol")).isTrue();
        assertThat(paper().allowsAsset(null)).isFalse();
        assertThat(paper().enablesStrategy("REBALANCE")).isTrue();
        assertThat(paper().enablesStrategy("rebalance")).isFalse(); // strategies are case-sensitive identifiers
    }

    @Test
    void canonicalizerRefusesWhatCannotBeHashedUnambiguously() {
        assertThatThrownBy(() -> CanonicalJson.canonicalize(java.util.Map.of("a", 1.5))).hasMessageContaining("not canonicalizable");
        java.util.Map<String, Object> withNull = new java.util.HashMap<>();
        withNull.put("a", null);
        assertThatThrownBy(() -> CanonicalJson.canonicalize(withNull)).hasMessageContaining("null");
        assertThatThrownBy(() -> CanonicalJson.canonicalize(java.util.Map.of("a", CanonicalJson.MAX_SAFE_INTEGER + 1))).hasMessageContaining("safe range");
        assertThat(CanonicalJson.canonicalize(java.util.Map.of("q", "a\"b\\c\n"))).isEqualTo("{\"q\":\"a\\\"b\\\\c\\n\\u0001\"}");
    }
}
