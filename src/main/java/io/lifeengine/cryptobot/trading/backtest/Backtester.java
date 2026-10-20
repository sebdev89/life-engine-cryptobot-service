package io.lifeengine.cryptobot.trading.backtest;

import io.lifeengine.cryptobot.trading.backtest.BacktestStrategy.Holdings;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;

/**
 * Replays a SOL/USD close series through a {@link BacktestStrategy} and measures it net of costs (KAN-1216,
 * CryptoBot v2). Deterministic by construction: BigDecimal arithmetic with a fixed {@link MathContext}, SOL
 * truncated to lamports (9 decimals) and USDC to its 6 decimals, rounding always against the strategy. Same
 * bars, strategy and fees → byte-identical result.
 *
 * <p>Execution model: the capital is allocated for free at the first close (see {@link FeeModel}); then, at
 * every close (the first included), the strategy decides and the trade fills at that close moved
 * {@code slippageBps} against it, minus {@code feeBps}. A buy never spends more USDC than held and a sell
 * never sells more SOL than held. Equity is marked at each close after the trade.
 */
public final class Backtester {

    private static final MathContext MC = MathContext.DECIMAL64;
    private static final BigDecimal BPS = BigDecimal.valueOf(10_000);
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final int SOL_SCALE = 9;
    private static final int USD_SCALE = 6;
    private static final long SECONDS_PER_YEAR = 365L * 24 * 3600;

    private Backtester() {}

    public static BacktestResult run(List<PriceBar> bars, BacktestStrategy strategy, BigDecimal initialUsd, FeeModel fees) {
        Objects.requireNonNull(strategy, "strategy");
        Objects.requireNonNull(fees, "fees");
        if (initialUsd == null || initialUsd.signum() <= 0) {
            throw new IllegalArgumentException("initialUsd must be positive: " + initialUsd);
        }
        if (bars == null || bars.size() < 2) {
            throw new IllegalArgumentException("a backtest needs at least 2 bars");
        }
        for (int i = 1; i < bars.size(); i++) {
            if (!bars.get(i).time().isAfter(bars.get(i - 1).time())) {
                throw new IllegalArgumentException("bars must be strictly increasing in time (index " + i + ")");
            }
        }

        BigDecimal capital = initialUsd.setScale(USD_SCALE, RoundingMode.DOWN);
        BigDecimal firstClose = bars.get(0).close();
        BigDecimal solUsd = capital.multiply(strategy.initialSolWeight()).setScale(USD_SCALE, RoundingMode.DOWN);
        BigDecimal sol = solUsd.divide(firstClose, SOL_SCALE, RoundingMode.DOWN);
        BigDecimal usdc = capital.subtract(solUsd);

        BigDecimal feeRate = BigDecimal.valueOf(fees.feeBps()).divide(BPS, MC);
        BigDecimal slipRate = BigDecimal.valueOf(fees.slippageBps()).divide(BPS, MC);
        BigDecimal costs = BigDecimal.ZERO;
        int trades = 0;

        BigDecimal[] equity = new BigDecimal[bars.size() + 1];
        equity[0] = capital;
        BigDecimal exposureSum = BigDecimal.ZERO;
        for (int i = 0; i < bars.size(); i++) {
            PriceBar bar = bars.get(i);
            BigDecimal close = bar.close();
            BigDecimal notional = strategy.decide(i, bar, new Holdings(sol, usdc));
            if (notional != null && notional.signum() > 0) {
                BigDecimal spend = notional.min(usdc).setScale(USD_SCALE, RoundingMode.DOWN);
                if (spend.signum() > 0) {
                    BigDecimal net = spend.subtract(spend.multiply(feeRate, MC));
                    BigDecimal fillPrice = close.multiply(BigDecimal.ONE.add(slipRate), MC);
                    BigDecimal bought = net.divide(fillPrice, SOL_SCALE, RoundingMode.DOWN);
                    usdc = usdc.subtract(spend);
                    sol = sol.add(bought);
                    costs = costs.add(spend.subtract(bought.multiply(close)));
                    trades++;
                }
            } else if (notional != null && notional.signum() < 0) {
                BigDecimal sold = notional.negate().divide(close, SOL_SCALE, RoundingMode.DOWN).min(sol);
                if (sold.signum() > 0) {
                    BigDecimal gross = sold.multiply(close).multiply(BigDecimal.ONE.subtract(slipRate), MC);
                    BigDecimal received = gross.subtract(gross.multiply(feeRate, MC)).setScale(USD_SCALE, RoundingMode.DOWN);
                    sol = sol.subtract(sold);
                    usdc = usdc.add(received);
                    costs = costs.add(sold.multiply(close).subtract(received));
                    trades++;
                }
            }
            equity[i + 1] = sol.multiply(close).add(usdc).setScale(USD_SCALE, RoundingMode.DOWN);
            if (equity[i + 1].signum() > 0) {
                exposureSum = exposureSum.add(sol.multiply(close).divide(equity[i + 1], MC));
            }
        }

        BigDecimal finalUsd = equity[bars.size()];
        BigDecimal pnl = finalUsd.subtract(capital);
        Duration span = Duration.between(bars.get(0).time(), bars.get(bars.size() - 1).time());
        long barSeconds = Math.max(1, span.toSeconds() / (bars.size() - 1));
        long barsPerYear = SECONDS_PER_YEAR / barSeconds;
        BigDecimal[] returns = returns(equity);
        BigDecimal returnPct = pnl.multiply(HUNDRED).divide(capital, 4, RoundingMode.HALF_EVEN);
        BigDecimal exposurePct = exposureSum.multiply(HUNDRED).divide(BigDecimal.valueOf(bars.size()), 4, RoundingMode.HALF_EVEN);
        BigDecimal returnOnExposurePct = exposurePct.signum() == 0
                ? BigDecimal.ZERO.setScale(4)
                : returnPct.multiply(HUNDRED).divide(exposurePct, 4, RoundingMode.HALF_EVEN);

        return new BacktestResult(
                strategy.id(),
                bars.get(0).time(),
                bars.get(bars.size() - 1).time(),
                bars.size(),
                capital,
                finalUsd,
                pnl,
                returnPct,
                maxDrawdownPct(equity),
                sharpe(returns, barsPerYear),
                sortino(returns, barsPerYear),
                worstMonthPct(bars, equity),
                exposurePct,
                returnOnExposurePct,
                trades,
                costs.setScale(USD_SCALE, RoundingMode.UP),
                sol,
                usdc);
    }

    /** Largest peak-to-trough fall of the equity curve, in percent of the peak. */
    static BigDecimal maxDrawdownPct(BigDecimal[] equity) {
        BigDecimal peak = equity[0];
        BigDecimal worst = BigDecimal.ZERO;
        for (BigDecimal e : equity) {
            if (e.compareTo(peak) > 0) {
                peak = e;
            } else if (peak.signum() > 0) {
                BigDecimal dd = peak.subtract(e).divide(peak, MC);
                if (dd.compareTo(worst) > 0) {
                    worst = dd;
                }
            }
        }
        return worst.multiply(HUNDRED).setScale(4, RoundingMode.HALF_EVEN);
    }

    /** Per-bar returns of the equity curve (equity[0] is the capital before the first close). */
    static BigDecimal[] returns(BigDecimal[] equity) {
        BigDecimal[] r = new BigDecimal[equity.length - 1];
        for (int i = 1; i < equity.length; i++) {
            r[i - 1] = equity[i - 1].signum() == 0 ? BigDecimal.ZERO : equity[i].divide(equity[i - 1], MC).subtract(BigDecimal.ONE);
        }
        return r;
    }

    private static BigDecimal mean(BigDecimal[] r) {
        BigDecimal sum = BigDecimal.ZERO;
        for (BigDecimal x : r) {
            sum = sum.add(x);
        }
        return sum.divide(BigDecimal.valueOf(r.length), MC);
    }

    private static BigDecimal annualized(BigDecimal mean, BigDecimal deviation, long barsPerYear) {
        if (deviation.signum() == 0) {
            return BigDecimal.ZERO.setScale(4);
        }
        return mean.divide(deviation, MC).multiply(BigDecimal.valueOf(barsPerYear).sqrt(MC), MC).setScale(4, RoundingMode.HALF_EVEN);
    }

    /** Annualized Sharpe of the per-bar returns (sample standard deviation, risk-free 0); 0 when flat. */
    static BigDecimal sharpe(BigDecimal[] r, long barsPerYear) {
        if (r.length < 2) {
            return BigDecimal.ZERO.setScale(4);
        }
        BigDecimal mean = mean(r);
        BigDecimal sq = BigDecimal.ZERO;
        for (BigDecimal x : r) {
            BigDecimal d = x.subtract(mean);
            sq = sq.add(d.multiply(d, MC));
        }
        return annualized(mean, sq.divide(BigDecimal.valueOf(r.length - 1), MC).sqrt(MC), barsPerYear);
    }

    /** Annualized Sortino: like Sharpe, but only the bars that lost count as risk (downside deviation, target 0). */
    static BigDecimal sortino(BigDecimal[] r, long barsPerYear) {
        if (r.length < 2) {
            return BigDecimal.ZERO.setScale(4);
        }
        BigDecimal sq = BigDecimal.ZERO;
        for (BigDecimal x : r) {
            if (x.signum() < 0) {
                sq = sq.add(x.multiply(x, MC));
            }
        }
        return annualized(mean(r), sq.divide(BigDecimal.valueOf(r.length), MC).sqrt(MC), barsPerYear);
    }

    /**
     * Worst calendar month (UTC) in percent: equity at the month's last close over equity at the previous
     * month's last close (the capital for the first month). The first and last months may be partial.
     */
    static BigDecimal worstMonthPct(List<PriceBar> bars, BigDecimal[] equity) {
        BigDecimal start = equity[0];
        BigDecimal worst = null;
        for (int i = 0; i < bars.size(); i++) {
            boolean lastOfMonth = i == bars.size() - 1
                    || !YearMonth.from(bars.get(i).time().atOffset(ZoneOffset.UTC))
                            .equals(YearMonth.from(bars.get(i + 1).time().atOffset(ZoneOffset.UTC)));
            if (lastOfMonth) {
                BigDecimal month = start.signum() == 0 ? BigDecimal.ZERO : equity[i + 1].divide(start, MC).subtract(BigDecimal.ONE);
                worst = worst == null || month.compareTo(worst) < 0 ? month : worst;
                start = equity[i + 1];
            }
        }
        return worst.multiply(HUNDRED).setScale(4, RoundingMode.HALF_EVEN);
    }
}
