package io.lifeengine.cryptobot.infrastructure.persistence.controlplane;

import io.lifeengine.cryptobot.trading.portfolio.PortfolioSnapshot;
import java.util.UUID;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface PortfolioSnapshotRepository {
    Mono<PortfolioSnapshot> insert(PortfolioSnapshot snapshot);

    Mono<PortfolioSnapshot> findById(UUID id);

    /** Newest first. {@code limit} is capped by the store. */
    Flux<PortfolioSnapshot> findRecent(UUID walletId, int limit);
}
