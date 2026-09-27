package io.lifeengine.cryptobot.application.reliability;

import io.lifeengine.cryptobot.core.reliability.DeadLetter;
import io.lifeengine.cryptobot.core.reliability.OutboxEvent;
import io.lifeengine.cryptobot.infrastructure.persistence.controlplane.DeadLetterRepository;
import io.lifeengine.cryptobot.infrastructure.persistence.controlplane.OutboxRepository;
import io.lifeengine.cryptobot.observability.CryptobotMetrics;
import io.lifeengine.cryptobot.observability.ErrorCode;
import io.lifeengine.cryptobot.observability.LogFields;
import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * The outbox worker (KAN-403 §31 "mensaje perdido → outbox"). Every tick locks a batch of due
 * {@code PENDING} events ({@code FOR UPDATE SKIP LOCKED}), hands each to the {@link OutboxSink}
 * and records the outcome in the same transaction: {@code PUBLISHED}, or {@code PENDING} again
 * with exponential backoff, or — after {@code maxAttempts} — {@code FAILED} plus a dead letter.
 * Nothing is ever dropped. The gauges {@code outbox_pending}, {@code outbox_failed} and
 * {@code dlq_size} are refreshed after each tick.
 */
@Component
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final OutboxRepository outbox;
    private final DeadLetterRepository deadLetters;
    private final OutboxSink sink;
    private final CryptobotMetrics metrics;
    private final ReliabilityProperties.Outbox config;
    private final Clock clock;
    private volatile Disposable loop;

    @org.springframework.beans.factory.annotation.Autowired
    public OutboxPublisher(OutboxRepository outbox, DeadLetterRepository deadLetters, OutboxSink sink, CryptobotMetrics metrics, ReliabilityProperties properties) {
        this(outbox, deadLetters, sink, metrics, properties, Clock.systemUTC());
    }

    /** Clock injectable for tests (backoff timestamps). */
    public OutboxPublisher(OutboxRepository outbox, DeadLetterRepository deadLetters, OutboxSink sink, CryptobotMetrics metrics, ReliabilityProperties properties, Clock clock) {
        this.outbox = outbox;
        this.deadLetters = deadLetters;
        this.sink = sink;
        this.metrics = metrics;
        this.config = properties.outbox();
        this.clock = clock;
    }

    @EventListener(ApplicationReadyEvent.class)
    void start() {
        if (!config.enabled()) {
            log.info("outbox_publisher_disabled — events are written but not published");
            return;
        }
        Duration every = config.pollInterval();
        log.info("outbox_publisher_starting pollInterval={} batchSize={} maxAttempts={}", every, config.batchSize(), config.maxAttempts());
        loop = Flux.interval(every, every)
                .onBackpressureDrop()
                .concatMap(tick -> tick().onErrorResume(ex -> {
                    log.warn("outbox_tick_failed error={}", ex.toString());
                    return Mono.empty();
                }))
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe();
    }

    @PreDestroy
    void stop() {
        if (loop != null && !loop.isDisposed()) {
            loop.dispose();
        }
    }

    /** One pass: process what is due, then refresh the gauges. Returns the number of events handled. */
    public Mono<Long> tick() {
        Instant now = clock.instant();
        return outbox.processDue(now, config.batchSize(), this::deliver)
                .flatMap(n -> refreshGauges().thenReturn(n))
                .doOnNext(n -> {
                    if (n > 0) {
                        log.info("outbox_tick processed={}", n);
                    }
                });
    }

    private Mono<OutboxRepository.Outcome> deliver(OutboxEvent e) {
        return sink.publish(e)
                .then(Mono.fromSupplier(() -> (OutboxRepository.Outcome) new OutboxRepository.Outcome.Published(clock.instant())))
                .onErrorResume(ex -> {
                    int attempt = e.attempts() + 1;
                    String error = ex.toString();
                    if (attempt >= config.maxAttempts()) {
                        log.error("outbox_event_dead eventId={} type={} aggregateId={} attempts={} error={} — DLQ", e.id(), e.eventType(), e.aggregateId(), attempt, error,
                                LogFields.event("outbox_dead"), LogFields.status("dead"), ErrorCode.OUTBOX_DEAD.kv(),
                                LogFields.proposalId(OutboxEvent.AGGREGATE_PROPOSAL.equals(e.aggregateType()) ? e.aggregateId() : null));
                        DeadLetter letter = DeadLetter.of(DeadLetter.Source.OUTBOX, e.id(),
                                OutboxEvent.AGGREGATE_PROPOSAL.equals(e.aggregateType()) ? e.aggregateId() : null, e.ownerUserId(),
                                "Outbox delivery failed after " + attempt + " attempts: " + error,
                                Map.of("eventType", e.eventType(), "aggregateType", e.aggregateType(), "kind", "outbox"), clock.instant());
                        metrics.deadLetter("outbox");
                        return Mono.just(new OutboxRepository.Outcome.Dead(letter, error));
                    }
                    Instant next = clock.instant().plus(backoff(attempt));
                    log.warn("outbox_event_retry eventId={} type={} attempt={} nextAttemptAt={} error={}", e.id(), e.eventType(), attempt, next, error,
                            LogFields.event("outbox_retry"), LogFields.status("retrying"));
                    return Mono.just(new OutboxRepository.Outcome.Retry(next, error));
                });
    }

    /** {@code initial × 2^(attempt-1)}, capped at {@code maxBackoff}. */
    Duration backoff(int attempt) {
        long millis = config.initialBackoff().toMillis();
        for (int i = 1; i < attempt && millis < config.maxBackoff().toMillis(); i++) {
            millis *= 2;
        }
        return Duration.ofMillis(Math.min(millis, config.maxBackoff().toMillis()));
    }

    private Mono<Void> refreshGauges() {
        return outbox.countByStatus(OutboxEvent.Status.PENDING).doOnNext(metrics::outboxPending)
                .then(outbox.countByStatus(OutboxEvent.Status.FAILED).doOnNext(metrics::outboxFailed))
                .then(deadLetters.countUnresolved().doOnNext(metrics::dlqSize))
                .then();
    }
}
