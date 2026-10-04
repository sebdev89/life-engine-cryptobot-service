package io.lifeengine.cryptobot.application.controlplane;

import io.lifeengine.cryptobot.core.policy.PolicyRules;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Source of the versioned policy {@code R_v}. USD amounts here are converted to cents
 * and the rest to integers once, at startup, into a {@link PolicyRules}; the hash of that record
 * — not of this file — is what every verdict commits to. {@code max-trade-usd} and
 * {@code allowed-assets} are shared with the legacy rules ({@code cryptobot.policy.*}) so there is
 * one cap, not two.
 */
@ConfigurationProperties(prefix = "cryptobot.policy.authorization")
public record AuthorizationProperties(
        String version,
        BigDecimal autonomousUpToUsd,
        BigDecimal secondAgentUpToUsd,
        BigDecimal dailyLimitUsd,
        Integer maxAssetExposureBps,
        Integer maxSlippageBps,
        Integer executorSlippageBps,
        Duration maxOracleAge,
        List<String> enabledStrategies) {

    public AuthorizationProperties {
        version = version == null || version.isBlank() ? "cryptobot-policy-v1" : version.trim();
        autonomousUpToUsd = autonomousUpToUsd == null ? new BigDecimal("100") : autonomousUpToUsd;
        secondAgentUpToUsd = secondAgentUpToUsd == null ? new BigDecimal("250") : secondAgentUpToUsd;
        dailyLimitUsd = dailyLimitUsd == null ? new BigDecimal("2500") : dailyLimitUsd;
        maxAssetExposureBps = maxAssetExposureBps == null ? 8_000 : maxAssetExposureBps;
        maxSlippageBps = maxSlippageBps == null ? 100 : maxSlippageBps;
        executorSlippageBps = executorSlippageBps == null ? 50 : executorSlippageBps;
        maxOracleAge = maxOracleAge == null ? Duration.ofMinutes(15) : maxOracleAge;
        enabledStrategies = enabledStrategies == null || enabledStrategies.isEmpty() ? List.of("REBALANCE") : List.copyOf(enabledStrategies);
    }

    /** The committed policy. Throws at startup if the tiers are not monotonic (fail-closed: no policy, no service). */
    public PolicyRules rules(PolicyProperties base) {
        return new PolicyRules(
                version,
                base.allowedAssets(),
                enabledStrategies,
                cents(base.maxTradeUsd()),
                cents(dailyLimitUsd),
                maxAssetExposureBps,
                maxSlippageBps,
                maxOracleAge.toSeconds(),
                cents(autonomousUpToUsd),
                cents(secondAgentUpToUsd));
    }

    /** USD limit → cents, rounding <em>down</em>: quantization never widens a limit. */
    static long cents(BigDecimal usd) {
        return usd.movePointRight(2).setScale(0, RoundingMode.FLOOR).longValueExact();
    }

    /** USD trade value → cents, rounding <em>up</em>: quantization never shrinks a trade. */
    static long tradeCents(BigDecimal usd) {
        return usd.movePointRight(2).setScale(0, RoundingMode.CEILING).longValueExact();
    }
}
