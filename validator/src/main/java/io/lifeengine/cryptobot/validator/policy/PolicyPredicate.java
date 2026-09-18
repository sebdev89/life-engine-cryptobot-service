package io.lifeengine.cryptobot.validator.policy;

/**
 * {@code P_1..P_n} of the policy layer (paper §8), in evaluation order. {@code Valid(I) = ∧ P_i}:
 * one failed predicate is a DENY, and the verdict names every one that failed so the audit trail
 * and the receipt say exactly why.
 *
 * <p>The order is part of the model: the {@code failed_predicates} list in the verdict follows
 * it, and the verdict hash covers that list. Reordering is a policy schema change.
 */
public enum PolicyPredicate {
    /** The intent was built for this policy version (§23, policy-binding invariant). */
    POLICY_BOUND("intent.policy_version == rules.version"),
    /** {@code asset ∈ allowed_assets}. */
    ASSET_ALLOWED("asset in allowed_assets"),
    /** {@code trade_value ≤ max_trade_value}. */
    TRADE_WITHIN_MAX("trade_value_cents <= max_trade_value_cents"),
    /** {@code daily_exposure + trade_value ≤ daily_limit}. */
    DAILY_LIMIT("daily_exposure_cents + trade_value_cents <= daily_limit_cents"),
    /** {@code asset_exposure_after ≤ max_asset_exposure}. */
    ASSET_CONCENTRATION("asset_exposure_after_bps <= max_asset_exposure_bps"),
    /** {@code slippage ≤ maximum_slippage}. */
    SLIPPAGE_WITHIN_MAX("max_slippage_bps <= rules.max_slippage_bps"),
    /** {@code oracle_age ≤ maximum_oracle_age}. */
    ORACLE_FRESH("oracle_age_seconds <= max_oracle_age_seconds"),
    /** {@code agent_permission = TRUE}. */
    AGENT_PERMITTED("agent_permitted == true"),
    /** {@code strategy_enabled = TRUE}. */
    STRATEGY_ENABLED("strategy_id in enabled_strategies"),
    /** {@code nonce_unused = TRUE}. */
    NONCE_UNUSED("nonce_unused == true"),
    /** {@code current_slot ≤ expiration_slot}. */
    NOT_EXPIRED("current_slot <= valid_until_slot");

    private final String formula;

    PolicyPredicate(String formula) {
        this.formula = formula;
    }

    /** The invariant as code, for humans reading a verdict. */
    public String formula() {
        return formula;
    }
}
