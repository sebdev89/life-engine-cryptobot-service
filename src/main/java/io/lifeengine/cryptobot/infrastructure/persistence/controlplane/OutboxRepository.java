package io.lifeengine.cryptobot.infrastructure.persistence.controlplane;

import io.lifeengine.cryptobot.domain.reliability.DeadLetter;
import io.lifeengine.cryptobot.domain.reliability.OutboxEvent;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Function;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * The outbox table. Events are <em>inserted</em> only through
 * {@link ActionProposalRepository#commit} (same transaction as the state); this interface is the
 * publisher's side and the read side.
 */
public interface OutboxRepository {

    /** What the publisher decided about one event; the repository persists it inside the batch transaction. */
    sealed interface Outcome {
        record Published(Instant at) implements Outcome {}

        record Retry(Instant nextAttemptAt, String error) implements Outcome {}

        /** Retries exhausted: mark {@code FAILED} and write the dead letter, atomically. */
        record Dead(DeadLetter letter, String error) implements Outcome {}
    }

    /**
     * Locks a batch of due {@code PENDING} events ({@code SELECT … FOR UPDATE SKIP LOCKED}), runs
     * {@code work} on each and persists the outcome — all in one transaction, so two publishers
     * never see the same event and a crash mid-batch leaves the events {@code PENDING}. Returns the
     * number of events processed.
     */
    Mono<Long> processDue(Instant now, int batchSize, Function<OutboxEvent, Mono<Outcome>> work);

    /** All events of one aggregate, oldest first. Owner scoping is the caller's job (it has the proposal). */
    Flux<OutboxEvent> findByAggregate(UUID aggregateId);

    Mono<Long> countByStatus(OutboxEvent.Status status);
}
