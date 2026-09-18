package io.lifeengine.cryptobot.validator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class PolicyStoreTest {

    static ValidatorProperties.Policy policy(String expectedHash) {
        return new ValidatorProperties.Policy("cryptobot-policy-v1", List.of("SOL", "USDC", "USDT"), List.of("REBALANCE"),
                50_000L, 250_000L, 8_000, 100, 900L, 10_000L, 25_000L, expectedHash);
    }

    static ValidatorProperties props(ValidatorProperties.Policy p) {
        return new ValidatorProperties("t", "", "", Duration.ofSeconds(90), true, p);
    }

    @Test
    void loadsAndPinsWhenTheHashMatches() {
        PolicyStore unpinned = new PolicyStore(props(policy("")));
        assertThat(unpinned.pinned()).isFalse();
        assertThat(unpinned.hash()).startsWith("sha256:");
        // The service's defaults ($500 / $2500 / $100 / $250, 15 min) in cents and seconds hash to exactly this.
        assertThat(unpinned.rules().canonicalJson()).isEqualTo("{\"allowed_assets\":[\"SOL\",\"USDC\",\"USDT\"],\"autonomous_up_to_cents\":10000,"
                + "\"daily_limit_cents\":250000,\"enabled_strategies\":[\"REBALANCE\"],\"max_asset_exposure_bps\":8000,\"max_oracle_age_seconds\":900,"
                + "\"max_slippage_bps\":100,\"max_trade_value_cents\":50000,\"schema_version\":\"1\",\"second_agent_up_to_cents\":25000,"
                + "\"version\":\"cryptobot-policy-v1\"}");

        PolicyStore pinned = new PolicyStore(props(policy(unpinned.hash())));
        assertThat(pinned.pinned()).isTrue();
        assertThat(pinned.hash()).isEqualTo(unpinned.hash());
    }

    @Test
    void refusesToStartWhenThePinnedHashIsAnotherPolicy() {
        assertThatThrownBy(() -> new PolicyStore(props(policy("sha256:" + "0".repeat(64)))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("policy hash mismatch");
    }

    @Test
    void refusesToStartWithoutALimit() {
        ValidatorProperties.Policy missing = new ValidatorProperties.Policy("v", List.of("SOL"), List.of("REBALANCE"),
                null, 250_000L, 8_000, 100, 900L, 10_000L, 25_000L, "");
        assertThatThrownBy(() -> new PolicyStore(props(missing)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("max-trade-value-cents is missing");
    }

    @Test
    void refusesNonMonotonicTiers() {
        ValidatorProperties.Policy broken = new ValidatorProperties.Policy("v", List.of("SOL"), List.of("REBALANCE"),
                50_000L, 250_000L, 8_000, 100, 900L, 30_000L, 25_000L, "");
        assertThatThrownBy(() -> new PolicyStore(props(broken)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("autonomous_up_to_cents");
    }
}
