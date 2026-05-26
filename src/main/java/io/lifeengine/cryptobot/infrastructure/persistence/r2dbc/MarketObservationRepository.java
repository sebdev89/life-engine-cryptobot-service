package io.lifeengine.cryptobot.infrastructure.persistence.r2dbc;

import java.util.UUID;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;

public interface MarketObservationRepository extends ReactiveCrudRepository<MarketObservationRow, UUID> {

    @Query("SELECT * FROM market_observation WHERE symbol = :symbol ORDER BY observed_at DESC LIMIT :limit")
    Flux<MarketObservationRow> findRecentBySymbol(String symbol, int limit);
}
