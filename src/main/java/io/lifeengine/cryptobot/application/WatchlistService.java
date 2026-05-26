package io.lifeengine.cryptobot.application;

import io.lifeengine.cryptobot.domain.WatchlistEntry;
import io.lifeengine.cryptobot.infrastructure.persistence.r2dbc.WatchlistEntryRepository;
import io.lifeengine.cryptobot.infrastructure.persistence.r2dbc.WatchlistEntryRow;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
public class WatchlistService {

    private final WatchlistEntryRepository repository;

    public WatchlistService(WatchlistEntryRepository repository) {
        this.repository = repository;
    }

    public Flux<WatchlistEntry> listActive() {
        return repository.findAllActiveOrdered().map(WatchlistEntryRow::toDomain);
    }

    public Flux<WatchlistEntry> findBySymbol(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            return Flux.empty();
        }
        return repository.findBySymbol(symbol.trim().toUpperCase(Locale.ROOT))
                .map(WatchlistEntryRow::toDomain);
    }

    public Mono<WatchlistEntry> create(WatchlistEntry input) {
        Instant now = Instant.now();
        WatchlistEntry seeded = new WatchlistEntry(
                input.id() != null ? input.id() : UUID.randomUUID(),
                input.symbol() == null ? null : input.symbol().trim().toUpperCase(Locale.ROOT),
                input.displayName(),
                input.assetType(),
                input.sectorTheme(),
                input.exchange(),
                input.priority(),
                input.active(),
                input.notes(),
                input.createdAt() != null ? input.createdAt() : now,
                input.updatedAt() != null ? input.updatedAt() : now);
        return repository.save(WatchlistEntryRow.fromDomain(seeded)).map(WatchlistEntryRow::toDomain);
    }
}
