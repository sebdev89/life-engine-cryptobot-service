package io.lifeengine.cryptobot.validator;

import io.lifeengine.cryptobot.validator.policy.PolicyRules;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Loads {@code R_v} once at boot, computes {@code H_R} and checks it against the pinned hash.
 * Any problem — a missing limit, non-monotonic tiers, a hash that is not the pinned one — is an
 * exception in the constructor: the process does not start, and a process that is not running
 * cannot attest anything (fail-closed, paper §17).
 */
@Component
public class PolicyStore {

    private static final Logger log = LoggerFactory.getLogger(PolicyStore.class);

    private final PolicyRules rules;
    private final String hash;
    private final boolean pinned;

    public PolicyStore(ValidatorProperties props) {
        this.rules = load(props.policy());
        this.hash = rules.hash();
        String expected = props.policy().expectedHash();
        this.pinned = !expected.isEmpty();
        if (pinned && !expected.equals(hash)) {
            throw new IllegalStateException("validator policy hash mismatch: loaded " + hash + " but VALIDATOR_POLICY_HASH pins " + expected
                    + " — the policy on disk is not the one that was approved; refusing to start");
        }
        if (!pinned) {
            log.warn("validator_policy_unpinned version={} hash={} — set VALIDATOR_POLICY_HASH to this value to pin it", rules.version(), hash);
        } else {
            log.info("validator_policy_loaded version={} hash={} pinned=true", rules.version(), hash);
        }
    }

    public PolicyRules rules() {
        return rules;
    }

    /** {@code H_R} of the policy in force in this process. */
    public String hash() {
        return hash;
    }

    public boolean pinned() {
        return pinned;
    }

    static PolicyRules load(ValidatorProperties.Policy p) {
        require("version", p.version());
        require("max-trade-value-cents", p.maxTradeValueCents());
        require("daily-limit-cents", p.dailyLimitCents());
        require("max-asset-exposure-bps", p.maxAssetExposureBps());
        require("max-slippage-bps", p.maxSlippageBps());
        require("max-oracle-age-seconds", p.maxOracleAgeSeconds());
        require("autonomous-up-to-cents", p.autonomousUpToCents());
        require("second-agent-up-to-cents", p.secondAgentUpToCents());
        try {
            return new PolicyRules(
                    p.version(),
                    p.allowedAssets(),
                    p.enabledStrategies(),
                    p.maxTradeValueCents(),
                    p.dailyLimitCents(),
                    p.maxAssetExposureBps(),
                    p.maxSlippageBps(),
                    p.maxOracleAgeSeconds(),
                    p.autonomousUpToCents(),
                    p.secondAgentUpToCents());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("validator policy does not load: " + e.getMessage(), e);
        }
    }

    private static void require(String field, Object value) {
        if (value == null || (value instanceof String s && s.isBlank())) {
            throw new IllegalStateException("validator policy does not load: validator.policy." + field + " is missing (no default: an unknown limit is not a limit)");
        }
    }
}
