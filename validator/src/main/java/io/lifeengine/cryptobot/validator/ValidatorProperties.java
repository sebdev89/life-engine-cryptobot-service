package io.lifeengine.cryptobot.validator;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The validator's own configuration. {@link Policy} is its own copy of {@code R_v}, in the
 * integer units of the schema (cents, bps, seconds) — never converted, never defaulted from the
 * service. {@code expectedHash} pins {@code H_R}: if the loaded policy hashes to anything else the
 * process refuses to start (paper §17: a policy that cannot be verified must not exist).
 */
@ConfigurationProperties(prefix = "validator")
public record ValidatorProperties(
        String token,
        String keypairPath,
        String keypairJson,
        Duration attestationTtl,
        boolean enabled,
        Policy policy) {

    public ValidatorProperties {
        token = token == null ? "" : token.trim();
        keypairPath = keypairPath == null ? "" : keypairPath.trim();
        keypairJson = keypairJson == null ? "" : keypairJson.trim();
        attestationTtl = attestationTtl == null || attestationTtl.isNegative() || attestationTtl.isZero() ? Duration.ofSeconds(90) : attestationTtl;
        policy = policy == null ? new Policy(null, null, null, null, null, null, null, null, null, null, null) : policy;
    }

    /** Every limit is required: a missing value is not "unlimited", it is a policy that does not load. */
    public record Policy(
            String version,
            List<String> allowedAssets,
            List<String> enabledStrategies,
            Long maxTradeValueCents,
            Long dailyLimitCents,
            Integer maxAssetExposureBps,
            Integer maxSlippageBps,
            Long maxOracleAgeSeconds,
            Long autonomousUpToCents,
            Long secondAgentUpToCents,
            String expectedHash) {

        public Policy {
            expectedHash = expectedHash == null ? "" : expectedHash.trim();
        }
    }
}
