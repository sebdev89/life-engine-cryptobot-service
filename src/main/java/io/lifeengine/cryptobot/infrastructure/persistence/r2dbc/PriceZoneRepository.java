package io.lifeengine.cryptobot.infrastructure.persistence.r2dbc;

import java.util.UUID;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;

public interface PriceZoneRepository extends ReactiveCrudRepository<PriceZoneRow, UUID> {

    @Query("SELECT * FROM price_zone WHERE symbol = :symbol ORDER BY valid_from DESC")
    Flux<PriceZoneRow> findBySymbol(String symbol);
}
