package io.lifeengine.cryptobot.core.intent;

/**
 * The finite vocabulary an agent may use (paper §6). Anything outside this set is not an intent —
 * it is rejected before canonicalization, never "interpreted".
 *
 * <pre>Action ∈ {BUY, SELL, SWAP, CANCEL, HOLD, REBALANCE}</pre>
 */
public enum IntentAction {
    /** Acquire {@code output_asset} paying {@code input_amount} of {@code input_asset}. */
    BUY,
    /** Dispose of {@code input_amount} of {@code input_asset} into {@code output_asset}. */
    SELL,
    /** Exchange {@code input_amount} of {@code input_asset} for {@code output_asset}. */
    SWAP,
    /** Withdraw a previous intent, identified by its hash ({@code target_intent_hash}). */
    CANCEL,
    /** Do nothing; the explicit "no trade" decision, still recorded and hashed. */
    HOLD,
    /** Move the portfolio to {@code target_weights_bps} against {@code counter_asset}. */
    REBALANCE;

    /** Actions that move a concrete amount of one asset into another. */
    public boolean isTrade() {
        return this == BUY || this == SELL || this == SWAP;
    }
}
