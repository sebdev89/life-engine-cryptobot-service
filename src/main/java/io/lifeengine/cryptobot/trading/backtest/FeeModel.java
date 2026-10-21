package io.lifeengine.cryptobot.trading.backtest;

/**
 * Cost of one swap, in basis points of its notional: {@code feeBps} is what the venue keeps (Jupiter route
 * fee + the DEX pool fee), {@code slippageBps} how far the fill lands from the bar's close, always against
 * us. Both are charged on every trade, never on the initial allocation (every strategy and every benchmark
 * starts from the same capital, allocated for free at the first close, so the comparison is only about what
 * each one does afterwards).
 */
public record FeeModel(int feeBps, int slippageBps) {

    /** A conservative default for a SOL/USDC swap through Jupiter on mainnet: 0.30 % fee + 0.20 % slippage. */
    public static final FeeModel DEFAULT = new FeeModel(30, 20);

    public FeeModel {
        if (feeBps < 0 || feeBps >= 10_000 || slippageBps < 0 || slippageBps >= 10_000) {
            throw new IllegalArgumentException("bps out of range: fee=" + feeBps + " slippage=" + slippageBps);
        }
    }
}
