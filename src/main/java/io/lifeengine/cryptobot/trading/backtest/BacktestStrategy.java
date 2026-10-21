package io.lifeengine.cryptobot.trading.backtest;

import java.math.BigDecimal;

/**
 * A rule that, given the bar just closed and what it holds, says how many USD of SOL to buy (positive) or
 * sell (negative), or zero. Implementations are pure: same bars and same holdings → same answer, no clock,
 * no randomness, no I/O. That is what makes a backtest reproducible and comparable across runs.
 */
public interface BacktestStrategy {

    /** Stable id shown in the report ({@code hold-sol}, {@code band-rebalance}, {@code dca}). */
    String id();

    /** Fraction of the initial capital allocated to SOL at the first close, in [0, 1]. */
    BigDecimal initialSolWeight();

    /**
     * @param index position of {@code bar} in the series (0 = the first close, already allocated)
     * @return USD notional to trade at this close: {@code > 0} buy SOL, {@code < 0} sell SOL, {@code 0} hold
     */
    BigDecimal decide(int index, PriceBar bar, Holdings holdings);

    /** SOL and USDC held; {@code equityUsd} values the SOL at a given price. */
    record Holdings(BigDecimal sol, BigDecimal usdc) {

        public BigDecimal equityUsd(BigDecimal price) {
            return sol.multiply(price).add(usdc);
        }
    }
}
