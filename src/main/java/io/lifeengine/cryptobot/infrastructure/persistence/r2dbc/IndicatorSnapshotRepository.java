package io.lifeengine.cryptobot.infrastructure.persistence.r2dbc;

import java.util.UUID;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;

public interface IndicatorSnapshotRepository extends ReactiveCrudRepository<IndicatorSnapshotRow, UUID> {

    @Query("SELECT * FROM indicator_snapshot WHERE symbol = :symbol ORDER BY computed_at DESC LIMIT :limit")
    Flux<IndicatorSnapshotRow> findRecentBySymbol(String symbol, int limit);
}
