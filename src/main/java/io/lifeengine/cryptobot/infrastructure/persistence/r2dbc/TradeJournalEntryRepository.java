package io.lifeengine.cryptobot.infrastructure.persistence.r2dbc;

import java.util.UUID;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;

public interface TradeJournalEntryRepository extends ReactiveCrudRepository<TradeJournalEntryRow, UUID> {

    @Query("SELECT * FROM trade_journal_entry WHERE symbol = :symbol ORDER BY entry_time DESC LIMIT :limit")
    Flux<TradeJournalEntryRow> findRecentBySymbol(String symbol, int limit);
}
