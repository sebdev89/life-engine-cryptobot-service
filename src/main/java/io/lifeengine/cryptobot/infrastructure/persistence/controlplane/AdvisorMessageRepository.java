package io.lifeengine.cryptobot.infrastructure.persistence.controlplane;

import io.lifeengine.cryptobot.domain.advisor.AdvisorMessage;
import java.util.UUID;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface AdvisorMessageRepository {
    Mono<AdvisorMessage> insert(AdvisorMessage message);

    /** Oldest first, capped. */
    Flux<AdvisorMessage> findByWallet(UUID walletId, int limit);
}
