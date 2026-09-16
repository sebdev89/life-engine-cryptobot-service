package io.lifeengine.cryptobot.application.reliability;

import io.lifeengine.cryptobot.domain.reliability.OutboxEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * The only consumer until KAN-402: one structured log line per published event (Loki-searchable
 * by {@code eventType} and {@code proposalId}). Never logs the payload — it can carry a signature
 * and an explorer URL, which are public, but the rule is one line, bounded fields. A broker sink
 * replaces this bean (mark it {@code @Primary}); nothing else changes.
 */
@Component
public class LoggingOutboxSink implements OutboxSink {

    private static final Logger log = LoggerFactory.getLogger(LoggingOutboxSink.class);

    @Override
    public Mono<Void> publish(OutboxEvent event) {
        return Mono.fromRunnable(() -> log.info("outbox_event_published eventId={} type={} aggregate={} aggregateId={} attempt={}",
                event.id(), event.eventType(), event.aggregateType(), event.aggregateId(), event.attempts() + 1));
    }
}
