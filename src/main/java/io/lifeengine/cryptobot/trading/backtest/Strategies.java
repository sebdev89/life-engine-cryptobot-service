package io.lifeengine.cryptobot.trading.backtest;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.Locale;

/**
 * The strategies v2 backtests, and the two benchmarks every one of them must beat net of costs: holding SOL
 * and holding USDC ("doing nothing"). Kept deliberately simple: a strategy that only wins with many knobs is
 * fitting the past, not finding an edge.
 */
public final class Strategies {

    private static final MathContext MC = MathContext.DECIMAL64;
    private static final BigDecimal BPS = BigDecimal.valueOf(10_000);

    private Strategies() {}

    /** Benchmark: all in SOL at the first close, never trades. */
    public static BacktestStrategy holdSol() {
        return new Hold("hold-sol", BigDecimal.ONE);
    }

    /** Benchmark: all in USDC, never trades — "doing nothing". */
    public static BacktestStrategy holdUsdc() {
        return new Hold("hold-usdc", BigDecimal.ZERO);
    }

    /**
     * Keeps the SOL weight at {@code targetSolWeight}; trades back to the target only when the weight drifts
     * more than {@code bandBps} away from it (the band keeps costs from eating the rebalancing premium).
     */
    public static BacktestStrategy bandRebalance(BigDecimal targetSolWeight, int bandBps) {
        return new BandRebalance(targetSolWeight, bandBps);
    }

    /**
     * Starts in USDC and buys {@code amountUsd} of SOL every {@code everyBars} bars while USDC lasts
     * (dollar-cost averaging).
     */
    public static BacktestStrategy dca(BigDecimal amountUsd, int everyBars) {
        return new Dca(amountUsd, everyBars);
    }

    /**
     * Builds a strategy by id with defaults for what is not given (band 50 % ± 5 %); the single place both the backtest
     * API and paper trading turn a name and its knobs into a strategy.
     */
    public static BacktestStrategy named(String id, BigDecimal targetSolWeight, Integer bandBps, BigDecimal dcaAmountUsd, Integer dcaEveryBars) {
        String key = id == null ? "" : id.trim().toLowerCase(Locale.ROOT);
        return switch (key) {
            case "hold-sol" -> holdSol();
            case "hold-usdc" -> holdUsdc();
            case "band-rebalance" -> bandRebalance(
                    targetSolWeight == null ? new BigDecimal("0.5") : targetSolWeight, bandBps == null ? 500 : bandBps);
            case "dca" -> {
                if (dcaAmountUsd == null || dcaEveryBars == null) {
                    throw new IllegalArgumentException("dca needs dcaAmountUsd and dcaEveryBars");
                }
                yield dca(dcaAmountUsd, dcaEveryBars);
            }
            default -> throw new IllegalArgumentException("unknown strategy '" + id + "' (hold-sol, hold-usdc, band-rebalance, dca)");
        };
    }

    /** The knobs of a strategy as a canonical string ({@code targetSolWeight=0.5;bandBps=500}); paper trading freezes it. */
    public static String params(BacktestStrategy s) {
        return switch (s) {
            case BandRebalance b -> "targetSolWeight=" + b.targetSolWeight().stripTrailingZeros().toPlainString() + ";bandBps=" + b.bandBps();
            case Dca d -> "dcaAmountUsd=" + d.amountUsd().stripTrailingZeros().toPlainString() + ";dcaEveryBars=" + d.everyBars();
            default -> "";
        };
    }

    private record Hold(String id, BigDecimal initialSolWeight) implements BacktestStrategy {
        @Override
        public BigDecimal decide(int index, PriceBar bar, Holdings holdings) {
            return BigDecimal.ZERO;
        }
    }

    private record BandRebalance(BigDecimal targetSolWeight, int bandBps) implements BacktestStrategy {

        BandRebalance {
            if (targetSolWeight == null || targetSolWeight.signum() < 0 || targetSolWeight.compareTo(BigDecimal.ONE) > 0) {
                throw new IllegalArgumentException("targetSolWeight must be in [0, 1]: " + targetSolWeight);
            }
            if (bandBps <= 0 || bandBps >= 10_000) {
                throw new IllegalArgumentException("bandBps must be in (0, 10000): " + bandBps);
            }
        }

        @Override
        public String id() {
            return "band-rebalance";
        }

        @Override
        public BigDecimal initialSolWeight() {
            return targetSolWeight;
        }

        @Override
        public BigDecimal decide(int index, PriceBar bar, Holdings holdings) {
            BigDecimal equity = holdings.equityUsd(bar.close());
            if (equity.signum() <= 0) {
                return BigDecimal.ZERO;
            }
            BigDecimal solUsd = holdings.sol().multiply(bar.close());
            BigDecimal weight = solUsd.divide(equity, MC);
            BigDecimal band = BigDecimal.valueOf(bandBps).divide(BPS, MC);
            if (weight.subtract(targetSolWeight).abs().compareTo(band) <= 0) {
                return BigDecimal.ZERO;
            }
            return equity.multiply(targetSolWeight).subtract(solUsd).setScale(6, RoundingMode.DOWN);
        }
    }

    private record Dca(BigDecimal amountUsd, int everyBars) implements BacktestStrategy {

        Dca {
            if (amountUsd == null || amountUsd.signum() <= 0) {
                throw new IllegalArgumentException("amountUsd must be positive: " + amountUsd);
            }
            if (everyBars <= 0) {
                throw new IllegalArgumentException("everyBars must be positive: " + everyBars);
            }
        }

        @Override
        public String id() {
            return "dca";
        }

        @Override
        public BigDecimal initialSolWeight() {
            return BigDecimal.ZERO;
        }

        @Override
        public BigDecimal decide(int index, PriceBar bar, Holdings holdings) {
            if (index % everyBars != 0 || holdings.usdc().signum() <= 0) {
                return BigDecimal.ZERO;
            }
            return amountUsd.min(holdings.usdc());
        }
    }
}
