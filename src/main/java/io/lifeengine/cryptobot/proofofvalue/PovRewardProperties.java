package io.lifeengine.cryptobot.proofofvalue;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * KAN-822 (V5): the immediate reward of an ANCHORED ValueEvent.
 *
 * @param enabled {@code POV_REWARD_ENABLED}; off by default — the demo compose turns it on. Off ⇒ {@code distribute} is a 409.
 * @param poolLamports {@code POV_REWARD_POOL_LAMPORTS}: the pool per event (default 10 000 000 = 0.01 devnet SOL), split
 *     {@code floor(units / totalUnits × pool)} per identity. Never more than the signer's {@code SIGNER_MAX_LAMPORTS} per
 *     transaction: a payout above that cap is FAILED before anything is signed.
 * @param strategyId the {@code strategy_id} of the payout's intent in {@code (I, S)}: the deterministic policy and the independent
 *     validator allow it only if it is in their {@code enabled-strategies} (the demo enables {@code REBALANCE,POV_REWARD} on both).
 */
@ConfigurationProperties(prefix = "cryptobot.pov.reward")
public record PovRewardProperties(Boolean enabled, Long poolLamports, String strategyId) {

    public static final long DEFAULT_POOL_LAMPORTS = 10_000_000L;
    public static final String DEFAULT_STRATEGY = "POV_REWARD";

    public PovRewardProperties {
        enabled = enabled != null && enabled;
        poolLamports = poolLamports == null ? DEFAULT_POOL_LAMPORTS : poolLamports;
        if (poolLamports <= 0) {
            throw new IllegalArgumentException("cryptobot.pov.reward.pool-lamports must be > 0, got " + poolLamports);
        }
        strategyId = strategyId == null || strategyId.isBlank() ? DEFAULT_STRATEGY : strategyId.trim();
    }

    public boolean isEnabled() {
        return enabled;
    }
}
