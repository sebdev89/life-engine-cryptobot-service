package io.lifeengine.cryptobot.application.reliability;

import static org.assertj.core.api.Assertions.assertThat;

import io.lifeengine.cryptobot.core.reliability.DeadLetter;
import io.lifeengine.cryptobot.core.reliability.OutboxEvent;
import io.lifeengine.cryptobot.core.reliability.TradeEvents;
import io.lifeengine.cryptobot.observability.CryptobotMetrics;
import io.lifeengine.cryptobot.testsupport.InMemoryControlPlaneRepositories;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

/**
 * KAN-403 — the outbox worker on the in-memory outbox: publish, retry with exponential backoff,
 * dead-letter after {@code maxAttempts}, gauges refreshed.
 */
class OutboxPublisherTest {

    private static final Instant T0 = Instant.parse("2026-09-15T12:00:00Z");

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final CryptobotMetrics metrics = new CryptobotMetrics(registry, List.of("SOL"));
    private final ReliabilityProperties props = new ReliabilityProperties(
            new ReliabilityProperties.Outbox(true, Duration.ofSeconds(1), 10, 3, Duration.ofSeconds(1), Duration.ofSeconds(4)), null);
    private final List<OutboxEvent> delivered = new CopyOnWriteArrayList<>();
    private final AtomicInteger failuresLeft = new AtomicInteger();
    private final OutboxSink sink = e -> failuresLeft.getAndDecrement() > 0
            ? Mono.error(new IllegalStateException("sink down"))
            : Mono.fromRunnable(() -> delivered.add(e));
    private MutableClock clock;
    private OutboxPublisher publisher;

    @BeforeEach
    void setUp() {
        InMemoryControlPlaneRepositories.reset();
        clock = new MutableClock(T0);
        publisher = new OutboxPublisher(InMemoryControlPlaneRepositories.outbox(), InMemoryControlPlaneRepositories.deadLetters(), sink, metrics, props, clock);
    }

    private OutboxEvent pending(String type) {
        OutboxEvent e = OutboxEvent.pending(OutboxEvent.AGGREGATE_PROPOSAL, UUID.randomUUID(), UUID.randomUUID(), type, Map.of("k", "v"), clock.instant());
        InMemoryControlPlaneRepositories.OUTBOX.put(e.id(), e);
        return e;
    }

    private OutboxEvent stored(OutboxEvent e) {
        return InMemoryControlPlaneRepositories.OUTBOX.get(e.id());
    }

    private double gauge(String name) {
        return registry.get(name).gauge().value();
    }

    @Test
    @DisplayName("due events are published in order and marked PUBLISHED; gauges drop to 0")
    void publishesDueEvents() {
        OutboxEvent a = pending(TradeEvents.REQUESTED);
        clock.advance(Duration.ofMillis(1));
        OutboxEvent b = pending(TradeEvents.APPROVED);

        assertThat(publisher.tick().block()).isEqualTo(2);

        assertThat(delivered).extracting(OutboxEvent::id).containsExactly(a.id(), b.id());
        assertThat(stored(a).status()).isEqualTo(OutboxEvent.Status.PUBLISHED);
        assertThat(stored(a).publishedAt()).isEqualTo(clock.instant());
        assertThat(stored(b).attempts()).isEqualTo(1);
        assertThat(gauge("outbox.pending")).isZero();
        assertThat(gauge("outbox.failed")).isZero();
        assertThat(gauge("dlq.size")).isZero();
    }

    @Test
    @DisplayName("a failing sink retries with exponential backoff (1s, 2s) and succeeds on the third attempt")
    void retriesWithBackoff() {
        OutboxEvent e = pending(TradeEvents.SUBMITTED);
        failuresLeft.set(2);

        assertThat(publisher.tick().block()).isEqualTo(1);
        assertThat(stored(e).status()).isEqualTo(OutboxEvent.Status.PENDING);
        assertThat(stored(e).attempts()).isEqualTo(1);
        assertThat(stored(e).nextAttemptAt()).isEqualTo(T0.plusSeconds(1));
        assertThat(stored(e).lastError()).contains("sink down");
        assertThat(gauge("outbox.pending")).isEqualTo(1);

        // Not due yet: nothing happens.
        clock.set(T0.plusMillis(500));
        assertThat(publisher.tick().block()).isZero();

        clock.set(T0.plusSeconds(1));
        assertThat(publisher.tick().block()).isEqualTo(1);
        assertThat(stored(e).attempts()).isEqualTo(2);
        assertThat(stored(e).nextAttemptAt()).isEqualTo(T0.plusSeconds(1).plusSeconds(2));

        clock.set(T0.plusSeconds(3));
        assertThat(publisher.tick().block()).isEqualTo(1);
        assertThat(stored(e).status()).isEqualTo(OutboxEvent.Status.PUBLISHED);
        assertThat(stored(e).attempts()).isEqualTo(3);
        assertThat(delivered).hasSize(1);
        assertThat(InMemoryControlPlaneRepositories.DEAD_LETTERS).isEmpty();
    }

    @Test
    @DisplayName("after maxAttempts the event is FAILED and dead-lettered atomically; dlq_size and outbox_failed say so")
    void deadLettersAfterMaxAttempts() {
        OutboxEvent e = pending(TradeEvents.CONFIRMED);
        failuresLeft.set(100);

        publisher.tick().block();
        clock.set(T0.plusSeconds(1));
        publisher.tick().block();
        clock.set(T0.plusSeconds(3));
        publisher.tick().block();

        assertThat(stored(e).status()).isEqualTo(OutboxEvent.Status.FAILED);
        assertThat(stored(e).attempts()).isEqualTo(3);
        assertThat(InMemoryControlPlaneRepositories.DEAD_LETTERS).hasSize(1);
        DeadLetter letter = InMemoryControlPlaneRepositories.DEAD_LETTERS.get(0);
        assertThat(letter.source()).isEqualTo(DeadLetter.Source.OUTBOX);
        assertThat(letter.refId()).isEqualTo(e.id());
        assertThat(letter.proposalId()).isEqualTo(e.aggregateId());
        assertThat(letter.reason()).contains("3 attempts");
        assertThat(gauge("outbox.failed")).isEqualTo(1);
        assertThat(gauge("dlq.size")).isEqualTo(1);
        assertThat(gauge("outbox.pending")).isZero();

        // A FAILED event is never picked up again.
        clock.set(T0.plusSeconds(60));
        assertThat(publisher.tick().block()).isZero();
        assertThat(delivered).isEmpty();
    }

    @Test
    @DisplayName("backoff doubles from initialBackoff and is capped at maxBackoff")
    void backoffIsExponentialAndCapped() {
        assertThat(publisher.backoff(1)).isEqualTo(Duration.ofSeconds(1));
        assertThat(publisher.backoff(2)).isEqualTo(Duration.ofSeconds(2));
        assertThat(publisher.backoff(3)).isEqualTo(Duration.ofSeconds(4));
        assertThat(publisher.backoff(4)).isEqualTo(Duration.ofSeconds(4));
        assertThat(publisher.backoff(30)).isEqualTo(Duration.ofSeconds(4));
    }

    /** A clock the test moves by hand. */
    static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant start) {
            this.now = start;
        }

        void set(Instant t) {
            now = t;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
