package io.lifeengine.cryptobot.trading.backtest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Known-answer coverage of the v2 backtester (KAN-1216): every number below is worked out by hand. */
class BacktesterTest {

    private static final Instant T0 = Instant.parse("2026-10-01T00:00:00Z");
    private static final FeeModel FREE = new FeeModel(0, 0);
    private static final BigDecimal K = new BigDecimal("1000");

    private static List<PriceBar> hourly(String... closes) {
        List<PriceBar> out = new ArrayList<>();
        for (int i = 0; i < closes.length; i++) {
            out.add(new PriceBar(T0.plusSeconds(3600L * i), new BigDecimal(closes[i])));
        }
        return out;
    }

    @Test
    @DisplayName("hold USDC: nothing moves — PnL 0, drawdown 0, Sharpe 0, no trades")
    void holdUsdcIsFlat() {
        BacktestResult r = Backtester.run(hourly("100", "150", "80"), Strategies.holdUsdc(), K, FeeModel.DEFAULT);

        assertThat(r.finalUsd()).isEqualByComparingTo("1000");
        assertThat(r.pnlUsd()).isEqualByComparingTo("0");
        assertThat(r.maxDrawdownPct()).isEqualByComparingTo("0");
        assertThat(r.sharpe()).isEqualByComparingTo("0");
        assertThat(r.trades()).isZero();
        assertThat(r.costsUsd()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("hold SOL 100 → 150 → 120: +20 % and a 20 % drawdown from the 1500 peak; the allocation is free")
    void holdSolTracksThePrice() {
        BacktestResult r = Backtester.run(hourly("100", "150", "120"), Strategies.holdSol(), K, FeeModel.DEFAULT);

        assertThat(r.finalSol()).isEqualByComparingTo("10");
        assertThat(r.finalUsd()).isEqualByComparingTo("1200");
        assertThat(r.pnlUsd()).isEqualByComparingTo("200");
        assertThat(r.returnPct()).isEqualByComparingTo("20");
        assertThat(r.maxDrawdownPct()).isEqualByComparingTo("20");
        assertThat(r.trades()).isZero();
    }

    @Test
    @DisplayName("a buy pays fee and slippage: 100 USD at 100 with 30 + 20 bps → 0.995009980 SOL, 0.499002 USD of costs")
    void buyPaysFeeAndSlippage() {
        BacktestResult r = Backtester.run(hourly("100", "100"), Strategies.dca(new BigDecimal("100"), 10), K, FeeModel.DEFAULT);

        // 100 − 0.30 % = 99.7 USD; fill at 100 × 1.002 = 100.2 → 99.7 / 100.2 = 0.995009980… (truncated to lamports)
        assertThat(r.finalSol()).isEqualByComparingTo("0.995009980");
        assertThat(r.finalUsdc()).isEqualByComparingTo("900");
        assertThat(r.costsUsd()).isEqualByComparingTo("0.499002");
        assertThat(r.finalUsd()).isEqualByComparingTo("999.500998");
        assertThat(r.trades()).isEqualTo(1);
    }

    @Test
    @DisplayName("band rebalance 50 % ± 10 %: SOL doubles → weight 66.7 % → sells 250 USD back to 50/50")
    void bandRebalanceSellsTheDrift() {
        BacktestResult r = Backtester.run(hourly("100", "200"), Strategies.bandRebalance(new BigDecimal("0.5"), 1_000), K, FREE);

        assertThat(r.trades()).isEqualTo(1);
        assertThat(r.finalSol()).isEqualByComparingTo("3.75");
        assertThat(r.finalUsdc()).isEqualByComparingTo("750");
        assertThat(r.finalUsd()).isEqualByComparingTo("1500");
    }

    @Test
    @DisplayName("band rebalance stays put while the drift is inside the band")
    void bandRebalanceIgnoresSmallDrift() {
        BacktestResult r = Backtester.run(hourly("100", "110", "95"), Strategies.bandRebalance(new BigDecimal("0.5"), 1_000), K, FREE);

        assertThat(r.trades()).isZero();
    }

    @Test
    @DisplayName("DCA buys every N bars until the USDC runs out, never more than it holds")
    void dcaStopsWhenUsdcRunsOut() {
        BacktestResult r = Backtester.run(hourly("100", "100", "100", "100", "100"), Strategies.dca(new BigDecimal("400"), 2), K, FREE);

        // buys at index 0 (400), 2 (400) and 4 (the 200 left)
        assertThat(r.trades()).isEqualTo(3);
        assertThat(r.finalUsdc()).isEqualByComparingTo("0");
        assertThat(r.finalSol()).isEqualByComparingTo("10");
    }

    @Test
    @DisplayName("deterministic: the same inputs give an identical result")
    void deterministic() {
        List<PriceBar> bars = hourly("100", "103.17", "97.42", "120.9", "88.31", "101.55", "131.07");
        BacktestStrategy s = Strategies.bandRebalance(new BigDecimal("0.6"), 500);

        assertThat(Backtester.run(bars, s, K, FeeModel.DEFAULT)).isEqualTo(Backtester.run(bars, s, K, FeeModel.DEFAULT));
    }

    @Test
    @DisplayName("Sharpe: a steadily rising curve is positive, a falling one negative")
    void sharpeSign() {
        BacktestResult up = Backtester.run(hourly("100", "101", "103", "104", "106"), Strategies.holdSol(), K, FREE);
        BacktestResult down = Backtester.run(hourly("106", "104", "103", "101", "100"), Strategies.holdSol(), K, FREE);

        assertThat(up.sharpe()).isPositive();
        assertThat(down.sharpe()).isNegative();
    }

    @Test
    @DisplayName("report: in a market that only rises, holding SOL wins and the strategy does not beat doing nothing")
    void reportComparesAgainstBothBenchmarks() {
        BacktestReport rep = BacktestReport.of(hourly("100", "120", "140", "160"),
                Strategies.bandRebalance(new BigDecimal("0.5"), 500), K, FeeModel.DEFAULT);

        assertThat(rep.holdSol().finalUsd()).isEqualByComparingTo("1600");
        assertThat(rep.holdUsdc().finalUsd()).isEqualByComparingTo("1000");
        assertThat(rep.beatsHoldUsdc()).isTrue();
        assertThat(rep.beatsHoldSol()).isFalse();
        assertThat(rep.beatsDoingNothing()).isFalse();
    }

    @Test
    @DisplayName("rejects bars out of order, fewer than two bars and a non-positive capital")
    void rejectsBadInput() {
        List<PriceBar> unordered = List.of(new PriceBar(T0.plusSeconds(60), BigDecimal.TEN), new PriceBar(T0, BigDecimal.TEN));

        assertThatThrownBy(() -> Backtester.run(unordered, Strategies.holdSol(), K, FREE)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Backtester.run(hourly("100"), Strategies.holdSol(), K, FREE)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Backtester.run(hourly("100", "101"), Strategies.holdSol(), BigDecimal.ZERO, FREE))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
