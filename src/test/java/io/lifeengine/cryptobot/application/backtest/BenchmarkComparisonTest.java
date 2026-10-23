package io.lifeengine.cryptobot.application.backtest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.lifeengine.cryptobot.trading.backtest.PriceBar;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** KAN-1226: a stock is compared with the index over its own sessions, and a short history is not a claim. */
class BenchmarkComparisonTest {

    static final Instant D0 = Instant.parse("2025-10-22T13:30:00Z");

    static List<PriceBar> days(int first, int count) {
        return IntStream.range(first, first + count).mapToObj(i -> new PriceBar(D0.plusSeconds(86_400L * i), BigDecimal.valueOf(100 + i))).toList();
    }

    @Test
    @DisplayName("a symbol listed mid-window: the index is cut to the symbol's first and last session")
    void indexIsCutToTheSymbolsHistory() {
        BacktestService.Comparison c = BacktestService.Comparison.of(days(160, 91), days(0, 365));
        assertThat(c.benchmarkBars()).hasSize(91);
        assertThat(c.benchmarkBars().get(0).time()).isEqualTo(D0.plusSeconds(86_400L * 160));
        assertThat(c.window().from()).isEqualTo(D0.plusSeconds(86_400L * 160));
        assertThat(c.window().to()).isEqualTo(D0.plusSeconds(86_400L * 250));
        assertThat(c.window().symbolBars()).isEqualTo(91);
        assertThat(c.window().benchmarkBars()).isEqualTo(91);
        assertThat(c.window().lowConfidence()).isTrue();
    }

    @Test
    @DisplayName("the index is also cut at the symbol's last session")
    void indexIsCutAtTheEnd() {
        BacktestService.Comparison c = BacktestService.Comparison.of(days(0, 200), days(0, 365));
        assertThat(c.benchmarkBars()).hasSize(200);
        assertThat(c.window().lowConfidence()).isFalse();
    }

    @Test
    @DisplayName("fewer than 120 sessions on either side is low confidence; exactly 120 is not")
    void lowConfidenceBoundary() {
        assertThat(BacktestService.Comparison.of(days(0, 119), days(0, 365)).window().lowConfidence()).isTrue();
        assertThat(BacktestService.Comparison.of(days(0, 120), days(0, 365)).window().lowConfidence()).isFalse();
        // the symbol is long enough but the index is missing sessions inside its window
        assertThat(BacktestService.Comparison.of(days(0, 200), days(100, 50)).window().lowConfidence()).isTrue();
    }

    @Test
    @DisplayName("an index with no session inside the symbol's window is refused, not compared over nothing")
    void noOverlapIsRefused() {
        assertThatThrownBy(() -> BacktestService.Comparison.of(days(200, 10), days(0, 100))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no sessions");
    }
}
