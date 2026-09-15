package io.lifeengine.cryptobot.infrastructure.persistence.controlplane;

import io.lifeengine.cryptobot.domain.transactions.ActionProposal;
import java.util.UUID;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface ActionProposalRepository {
    Mono<ActionProposal> insert(ActionProposal proposal);

    Mono<ActionProposal> update(ActionProposal proposal);

    Mono<ActionProposal> findByIdAndOwner(UUID id, UUID ownerUserId);

    /** Newest first, capped. */
    Flux<ActionProposal> findByWallet(UUID walletId, int limit);

    /** Newest first, capped. */
    Flux<ActionProposal> findByOwner(UUID ownerUserId, int limit);
}
