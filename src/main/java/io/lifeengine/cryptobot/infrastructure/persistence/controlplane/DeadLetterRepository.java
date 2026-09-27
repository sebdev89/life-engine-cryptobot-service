package io.lifeengine.cryptobot.infrastructure.persistence.controlplane;

import io.lifeengine.cryptobot.core.reliability.DeadLetter;
import java.time.Instant;
import java.util.UUID;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface DeadLetterRepository {
    Mono<DeadLetter> append(DeadLetter letter);

    /** Oldest first. Owner scoping is the caller's job (it has the proposal). */
    Flux<DeadLetter> findByProposal(UUID proposalId);

    Mono<Long> countUnresolved();

    // ---- KAN-571 / KAN-501: the way out --------------------------------------------------------

    Mono<DeadLetter> findById(UUID id);

    /**
     * Global listing (admin), newest first. {@code resolved}: {@code null} = all, {@code false} =
     * open only, {@code true} = resolved only. {@code source} and {@code proposalId} narrow it;
     * {@code null} = any.
     */
    Flux<DeadLetter> findAll(Boolean resolved, DeadLetter.Source source, UUID proposalId, int limit, int offset);

    /**
     * Marks the letter resolved — once. Empty when the letter does not exist <em>or is already
     * resolved</em>, so two operators (or a replayed request) can never both "win": the caller
     * maps empty to 409.
     */
    Mono<DeadLetter> resolve(UUID id, Instant at, String by, String note, DeadLetter.Outcome outcome);
}
