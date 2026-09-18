package io.lifeengine.cryptobot.application.controlplane;

import static org.assertj.core.api.Assertions.assertThat;

import io.lifeengine.cryptobot.domain.policy.PolicyRules;
import org.junit.jupiter.api.Test;

/**
 * KAN-438: the service's default {@code R_v} (USD in {@code application.yml}, converted to cents at
 * startup) and the validator's default {@code R_v} ({@code validator/src/main/resources/application.yml},
 * already in cents) must be the same policy, or every execution is {@code POLICY_HASH_MISMATCH}.
 * The validator asserts the same canonical string in its own {@code PolicyStoreTest}; a drift on
 * either side fails one of the two.
 */
class DefaultPolicyHashParityTest {

    static final String VALIDATOR_DEFAULT_CANONICAL = "{\"allowed_assets\":[\"SOL\",\"USDC\",\"USDT\"],\"autonomous_up_to_cents\":10000,"
            + "\"daily_limit_cents\":250000,\"enabled_strategies\":[\"REBALANCE\"],\"max_asset_exposure_bps\":8000,\"max_oracle_age_seconds\":900,"
            + "\"max_slippage_bps\":100,\"max_trade_value_cents\":50000,\"schema_version\":\"1\",\"second_agent_up_to_cents\":25000,"
            + "\"version\":\"cryptobot-policy-v1\"}";

    @Test
    void serviceDefaultsHashToTheValidatorDefaults() {
        PolicyProperties base = new PolicyProperties(true, null, null, null, null, null, null, 0, null);
        AuthorizationProperties auth = new AuthorizationProperties(null, null, null, null, null, null, null, null, null);
        PolicyRules rules = auth.rules(base);
        assertThat(rules.canonicalJson()).isEqualTo(VALIDATOR_DEFAULT_CANONICAL);
        assertThat(rules.hash()).isEqualTo(new PolicyRules("cryptobot-policy-v1", java.util.List.of("SOL", "USDC", "USDT"), java.util.List.of("REBALANCE"),
                50_000, 250_000, 8_000, 100, 900, 10_000, 25_000).hash());
    }
}
