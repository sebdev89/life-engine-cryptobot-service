package io.lifeengine.cryptobot.application;

import io.lifeengine.cryptobot.domain.MarketObservation;
import io.lifeengine.cryptobot.infrastructure.persistence.r2dbc.MarketObservationRepository;
import io.lifeengine.cryptobot.infrastructure.persistence.r2dbc.MarketObservationRow;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
public class MarketObservationsService {

    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 200;

    private final MarketObservationRepository repository;

    public MarketObservationsService(MarketObservationRepository repository) {
        this.repository = repository;
    }

    public Flux<MarketObservation> findRecentBySymbol(String symbol, Integer requestedLimit) {
        if (symbol == null || symbol.isBlank()) {
            return Flux.empty();
        }
        int limit = clampLimit(requestedLimit);
        return repository.findRecentBySymbol(symbol.trim().toUpperCase(Locale.ROOT), limit)
                .map(MarketObservationRow::toDomain);
    }

    public Mono<MarketObservation> create(MarketObservation input) {
        Instant now = Instant.now();
        MarketObservation seeded = new MarketObservation(
                input.id() != null ? input.id() : UUID.randomUUID(),
                input.symbol() == null ? null : input.symbol().trim().toUpperCase(Locale.ROOT),
                input.venue(),
                input.observedAt() != null ? input.observedAt() : now,
                input.timeframe(),
                input.lastPrice(),
                input.changePct24h(),
                input.volumeQuote24h(),
                input.spreadBps(),
                input.liquidityScore(),
                input.regime(),
                input.createdAt() != null ? input.createdAt() : now);
        return repository.save(MarketObservationRow.fromDomain(seeded)).map(MarketObservationRow::toDomain);
    }

    static int clampLimit(Integer requestedLimit) {
        if (requestedLimit == null || requestedLimit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(requestedLimit, MAX_LIMIT);
    }
}
