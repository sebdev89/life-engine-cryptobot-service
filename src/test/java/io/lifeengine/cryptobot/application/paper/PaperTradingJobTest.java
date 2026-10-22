package io.lifeengine.cryptobot.application.paper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

/**
 * KAN-1276: the paper job fires on the wall clock (every multiple of {@code interval} plus {@code settle}), not
 * {@code interval} after the process started. Times are 2026-10-22 UTC; interval 5 min, settle 30 s, so ticks land at
 * hh:00:30, hh:05:30, … whatever the start.
 */
class PaperTradingJobTest {

    static final Duration FIVE = Duration.ofMinutes(5);
    static final Duration SETTLE = Duration.ofSeconds(30);

    static Instant at(String hms) {
        return Instant.parse("2026-10-22T" + hms + "Z");
    }

    static PaperProperties props() {
        return new PaperProperties(true, FIVE, null, null, null, null, null, null, SETTLE);
    }

    @Test
    void theFirstAlignedTickIsTheNextMultipleOfTheIntervalPlusTheSettle() {
        // just before a multiple+settle, exactly on it, and just after
        assertThat(PaperTradingJob.firstAlignedDelay(at("18:05:29.999"), FIVE, SETTLE)).isEqualTo(Duration.ofMillis(1));
        assertThat(PaperTradingJob.firstAlignedDelay(at("18:05:30"), FIVE, SETTLE)).isZero();
        assertThat(PaperTradingJob.firstAlignedDelay(at("18:05:30.001"), FIVE, SETTLE)).isEqualTo(FIVE.minusMillis(1));
        // just before and just after the multiple itself (the candle close, for hourly candles at hh:00)
        assertThat(PaperTradingJob.firstAlignedDelay(at("18:59:59"), FIVE, SETTLE)).isEqualTo(Duration.ofSeconds(31));
        assertThat(PaperTradingJob.firstAlignedDelay(at("19:00:01"), FIVE, SETTLE)).isEqualTo(Duration.ofSeconds(29));
        // the starts measured in UAT (KAN-1276): all three now land at the same phase
        for (String start : List.of("13:56:03", "16:50:25", "18:08:24")) {
            Instant first = at(start).plus(PaperTradingJob.firstAlignedDelay(at(start), FIVE, SETTLE));
            assertThat(first.getEpochSecond() % 300).as(start).isEqualTo(30);
        }
    }

    @Test
    void theScheduleIsACatchUpTickNowThenTheAlignedOnesEveryInterval() {
        PaperTradingJob job = new PaperTradingJob(null, props(), new SimpleMeterRegistry(), Clock.fixed(at("18:08:24"), ZoneOffset.UTC));

        // 18:08:24 → catch-up now, then 18:10:30 (2 min 06 s later), 18:15:30, 18:20:30
        StepVerifier.withVirtualTime(() -> job.schedule(at("18:08:24")).take(4))
                .expectSubscription()
                .expectNext(-1L)
                .expectNoEvent(Duration.ofSeconds(125))
                .thenAwait(Duration.ofSeconds(1))
                .expectNext(0L)
                .expectNoEvent(FIVE.minusSeconds(1))
                .thenAwait(Duration.ofSeconds(1))
                .expectNext(1L)
                .thenAwait(FIVE)
                .expectNext(2L)
                .verifyComplete();
    }

    @Test
    void theLatencyFromTheCandleCloseIsMeasured() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        PaperTradingJob job = new PaperTradingJob(null, props(), meters, Clock.fixed(at("19:00:30.5"), ZoneOffset.UTC));
        assertThat(meters.find(PaperTradingJob.METRIC_LATENCY).gauge().value()).isNaN(); // nothing applied yet

        job.observe(new PaperTradingService.TickResult(at("19:00:00"), 1,
                List.of(new PaperTradingService.Advance("hold-sol", PaperTradingService.Outcome.ADVANCED, 1))));
        assertThat(meters.find(PaperTradingJob.METRIC_LATENCY).gauge().value()).isEqualTo(30.5);

        // a tick that applied nothing leaves the last measurement alone
        job.observe(new PaperTradingService.TickResult(at("19:00:00"), 0,
                List.of(new PaperTradingService.Advance("hold-sol", PaperTradingService.Outcome.UP_TO_DATE, 0))));
        assertThat(meters.find(PaperTradingJob.METRIC_LATENCY).gauge().value()).isEqualTo(30.5);
    }

    @Test
    void aSettleThatIsNotShorterThanTheIntervalDoesNotStart() {
        assertThatThrownBy(() -> new PaperProperties(true, FIVE, null, null, null, null, null, null, FIVE))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("settle");
        assertThat(new PaperProperties(true, null, null, null, null, null, null, null, null).settle()).isEqualTo(SETTLE);
    }
}
