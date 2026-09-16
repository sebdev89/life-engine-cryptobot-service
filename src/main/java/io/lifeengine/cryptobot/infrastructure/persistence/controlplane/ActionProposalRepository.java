package io.lifeengine.cryptobot.infrastructure.persistence.controlplane;

import io.lifeengine.cryptobot.domain.transactions.ActionProposal;
import io.lifeengine.cryptobot.domain.transactions.ProposalTransition;
import java.time.Instant;
import java.util.UUID;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface ActionProposalRepository {
    Mono<ActionProposal> insert(ActionProposal proposal);

    /**
     * Applies a transition atomically: the row is updated only if it still has
     * {@code expectedStatus}/{@code expectedVersion}; its audit and outbox events are written in
     * the same transaction. Fails with {@code ControlPlaneExceptions.StaleProposal} when the guard
     * does not match and with {@code DuplicateOperation} when the operationId is taken (KAN-403).
     */
    Mono<ActionProposal> commit(ProposalTransition transition);

    Mono<ActionProposal> findByIdAndOwner(UUID id, UUID ownerUserId);

    /** Newest first, capped. */
    Flux<ActionProposal> findByWallet(UUID walletId, int limit);

    /** Newest first, capped. */
    Flux<ActionProposal> findByOwner(UUID ownerUserId, int limit);

    /**
     * Every proposal in an in-flight status ({@code EXECUTING}, {@code SUBMITTED}) whose last
     * update is older than {@code updatedBefore}, across all owners — the reconciler's work list.
     * Oldest first, capped.
     */
    Flux<ActionProposal> findInFlight(Instant updatedBefore, int limit);
}
