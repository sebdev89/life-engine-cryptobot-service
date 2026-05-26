package io.lifeengine.cryptobot.application;

import io.lifeengine.cryptobot.domain.TradeJournalEntry;
import io.lifeengine.cryptobot.infrastructure.persistence.r2dbc.TradeJournalEntryRepository;
import io.lifeengine.cryptobot.infrastructure.persistence.r2dbc.TradeJournalEntryRow;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
public class TradeJournalService {

    private static final int DEFAULT_LIMIT = 10;
    private static final int MAX_LIMIT = 100;

    private final TradeJournalEntryRepository repository;

    public TradeJournalService(TradeJournalEntryRepository repository) {
        this.repository = repository;
    }

    public Flux<TradeJournalEntry> findRecentBySymbol(String symbol, Integer requestedLimit) {
        if (symbol == null || symbol.isBlank()) {
            return Flux.empty();
        }
        int limit = clampLimit(requestedLimit);
        return repository.findRecentBySymbol(symbol.trim().toUpperCase(Locale.ROOT), limit)
                .map(TradeJournalEntryRow::toDomain);
    }

    public Mono<TradeJournalEntry> create(TradeJournalEntry input) {
        Instant now = Instant.now();
        TradeJournalEntry seeded = new TradeJournalEntry(
                input.id() != null ? input.id() : UUID.randomUUID(),
                input.symbol() == null ? null : input.symbol().trim().toUpperCase(Locale.ROOT),
                input.entryTime() != null ? input.entryTime() : now,
                input.title(),
                input.body(),
                input.sentiment(),
                input.tags(),
                input.linkedWatchlistId(),
                input.createdAt() != null ? input.createdAt() : now,
                input.updatedAt() != null ? input.updatedAt() : now);
        return repository.save(TradeJournalEntryRow.fromDomain(seeded)).map(TradeJournalEntryRow::toDomain);
    }

    static int clampLimit(Integer requestedLimit) {
        if (requestedLimit == null || requestedLimit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(requestedLimit, MAX_LIMIT);
    }
}
