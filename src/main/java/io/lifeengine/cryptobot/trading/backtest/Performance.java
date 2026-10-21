package io.lifeengine.cryptobot.trading.backtest;

import io.lifeengine.cryptobot.trading.backtest.BacktestStrategy.Holdings;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Turns an equity curve into a {@link BacktestResult} (KAN-1217: shared by the backtester and paper trading, so
 * both report the same metrics computed the same way). The curve is the capital before the first close followed
 * by the equity marked at each close after that close's trade.
 */
public final class Performance {

    private static final MathContext MC = Execution.MC;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final long SECONDS_PER_YEAR = 365L * 24 * 3600;

    private Performance() {}

    /** One close: the equity marked after its trade and the share of that equity held in SOL. */
    public record Mark(BigDecimal equityUsd, BigDecimal solWeight) {}

    public static BacktestResult measure(String strategyId, List<PriceBar> bars, BigDecimal capital, List<Mark> marks,
            int trades, BigDecimal costs, Holdings last) {
        if (bars.size() < 2 || marks.size() != bars.size()) {
            throw new IllegalArgumentException("need at least 2 bars and one mark per bar");
        }
        BigDecimal[] equity = new BigDecimal[bars.size() + 1];
        equity[0] = capital;
        BigDecimal exposureSum = BigDecimal.ZERO;
        for (int i = 0; i < marks.size(); i++) {
            equity[i + 1] = marks.get(i).equityUsd();
            exposureSum = exposureSum.add(marks.get(i).solWeight());
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
                strategyId,
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
                costs.setScale(Execution.USD_SCALE, RoundingMode.UP),
                last.sol(),
                last.usdc());
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
