package io.lifeengine.cryptobot.application.reliability;

import io.lifeengine.cryptobot.core.reliability.OutboxEvent;
import reactor.core.publisher.Mono;

/**
 * Where published outbox events go. Today ({@link LoggingOutboxSink}) that is the structured log
 * and the {@code PUBLISHED} row itself, which clients read through the proposal's events endpoint.
 * The broker (NATS JetStream, KAN-402) is a second implementation of this port: the outbox, the
 * publisher and the events do not change. A sink that fails is retried with backoff and
 * dead-lettered after {@code maxAttempts}; it must therefore be idempotent on the event id.
 */
public interface OutboxSink {
    Mono<Void> publish(OutboxEvent event);
}
