package io.lifeengine.cryptobot.trading.backtest;

import io.lifeengine.cryptobot.trading.backtest.BacktestStrategy.Holdings;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/**
 * The fill model shared by the backtester and paper trading (KAN-1217), so a paper result and a backtest
 * result mean exactly the same thing. A trade fills at the close moved {@code slippageBps} against it, minus
 * {@code feeBps}; SOL is truncated to lamports and USDC to 6 decimals, always against the strategy. A buy never
 * spends more USDC than held and a sell never sells more SOL than held.
 */
public final class Execution {

    static final MathContext MC = MathContext.DECIMAL64;
    static final int SOL_SCALE = 9;
    static final int USD_SCALE = 6;
    private static final BigDecimal BPS = BigDecimal.valueOf(10_000);

    private Execution() {}

    /** What a decision did: holdings after it, what it cost in fee + slippage (USD at the close), and whether it traded. */
    public record Fill(Holdings after, BigDecimal costUsd, boolean traded) {}

    /** Holdings for {@code capital} USD with {@code solWeight} of it in SOL at {@code close}; the allocation is free. */
    public static Holdings allocate(BigDecimal capital, BigDecimal solWeight, BigDecimal close) {
        BigDecimal usd = capital.setScale(USD_SCALE, RoundingMode.DOWN);
        BigDecimal solUsd = usd.multiply(solWeight).setScale(USD_SCALE, RoundingMode.DOWN);
        return new Holdings(solUsd.divide(close, SOL_SCALE, RoundingMode.DOWN), usd.subtract(solUsd));
    }

    /** @param notional USD of SOL to buy ({@code > 0}) or sell ({@code < 0}); null or zero holds */
    public static Fill fill(Holdings h, BigDecimal notional, BigDecimal close, FeeModel fees) {
        BigDecimal feeRate = BigDecimal.valueOf(fees.feeBps()).divide(BPS, MC);
        BigDecimal slipRate = BigDecimal.valueOf(fees.slippageBps()).divide(BPS, MC);
        if (notional != null && notional.signum() > 0) {
            BigDecimal spend = notional.min(h.usdc()).setScale(USD_SCALE, RoundingMode.DOWN);
            if (spend.signum() > 0) {
                BigDecimal net = spend.subtract(spend.multiply(feeRate, MC));
                BigDecimal fillPrice = close.multiply(BigDecimal.ONE.add(slipRate), MC);
                BigDecimal bought = net.divide(fillPrice, SOL_SCALE, RoundingMode.DOWN);
                return new Fill(new Holdings(h.sol().add(bought), h.usdc().subtract(spend)), spend.subtract(bought.multiply(close)), true);
            }
        } else if (notional != null && notional.signum() < 0) {
            BigDecimal sold = notional.negate().divide(close, SOL_SCALE, RoundingMode.DOWN).min(h.sol());
            if (sold.signum() > 0) {
                BigDecimal gross = sold.multiply(close).multiply(BigDecimal.ONE.subtract(slipRate), MC);
                BigDecimal received = gross.subtract(gross.multiply(feeRate, MC)).setScale(USD_SCALE, RoundingMode.DOWN);
                return new Fill(new Holdings(h.sol().subtract(sold), h.usdc().add(received)), sold.multiply(close).subtract(received), true);
            }
        }
        return new Fill(h, BigDecimal.ZERO, false);
    }

    /** Equity marked at {@code close}, truncated to USDC's 6 decimals. */
    public static BigDecimal equity(Holdings h, BigDecimal close) {
        return h.sol().multiply(close).add(h.usdc()).setScale(USD_SCALE, RoundingMode.DOWN);
    }
}
