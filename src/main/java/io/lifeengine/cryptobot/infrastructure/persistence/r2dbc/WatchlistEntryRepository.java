package io.lifeengine.cryptobot.infrastructure.persistence.r2dbc;

import java.util.UUID;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;

public interface WatchlistEntryRepository extends ReactiveCrudRepository<WatchlistEntryRow, UUID> {

    @Query("SELECT * FROM watchlist_entry WHERE active = TRUE ORDER BY priority ASC, symbol ASC")
    Flux<WatchlistEntryRow> findAllActiveOrdered();

    @Query("SELECT * FROM watchlist_entry WHERE symbol = :symbol ORDER BY priority ASC")
    Flux<WatchlistEntryRow> findBySymbol(String symbol);
}
