package io.lifeengine.cryptobot.infrastructure.persistence.controlplane;

import io.lifeengine.cryptobot.domain.reliability.DeadLetter;
import java.util.UUID;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface DeadLetterRepository {
    Mono<DeadLetter> append(DeadLetter letter);

    /** Oldest first. Owner scoping is the caller's job (it has the proposal). */
    Flux<DeadLetter> findByProposal(UUID proposalId);

    Mono<Long> countUnresolved();
}
