package io.lifeengine.cryptobot.application;

import io.lifeengine.cryptobot.domain.PriceZone;
import io.lifeengine.cryptobot.infrastructure.persistence.r2dbc.PriceZoneRepository;
import io.lifeengine.cryptobot.infrastructure.persistence.r2dbc.PriceZoneRow;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
public class PriceZonesService {

    private final PriceZoneRepository repository;

    public PriceZonesService(PriceZoneRepository repository) {
        this.repository = repository;
    }

    public Flux<PriceZone> findBySymbol(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            return Flux.empty();
        }
        return repository.findBySymbol(symbol.trim().toUpperCase(Locale.ROOT)).map(PriceZoneRow::toDomain);
    }

    public Mono<PriceZone> create(PriceZone input) {
        Instant now = Instant.now();
        PriceZone seeded = new PriceZone(
                input.id() != null ? input.id() : UUID.randomUUID(),
                input.symbol() == null ? null : input.symbol().trim().toUpperCase(Locale.ROOT),
                input.timeframe(),
                input.zoneKind(),
                input.lowerBound(),
                input.upperBound(),
                input.confidence(),
                input.validFrom() != null ? input.validFrom() : now,
                input.validUntil(),
                input.label(),
                input.createdAt() != null ? input.createdAt() : now,
                input.updatedAt() != null ? input.updatedAt() : now);
        return repository.save(PriceZoneRow.fromDomain(seeded)).map(PriceZoneRow::toDomain);
    }
}
