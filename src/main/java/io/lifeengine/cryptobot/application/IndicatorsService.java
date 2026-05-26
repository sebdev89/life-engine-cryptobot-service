package io.lifeengine.cryptobot.application;

import io.lifeengine.cryptobot.domain.IndicatorSnapshot;
import io.lifeengine.cryptobot.infrastructure.persistence.r2dbc.IndicatorSnapshotRepository;
import io.lifeengine.cryptobot.infrastructure.persistence.r2dbc.IndicatorSnapshotRow;
import java.util.Locale;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

@Service
public class IndicatorsService {

    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 200;

    private final IndicatorSnapshotRepository repository;

    public IndicatorsService(IndicatorSnapshotRepository repository) {
        this.repository = repository;
    }

    public Flux<IndicatorSnapshot> findRecentBySymbol(String symbol, Integer requestedLimit) {
        if (symbol == null || symbol.isBlank()) {
            return Flux.empty();
        }
        int limit = clampLimit(requestedLimit);
        return repository.findRecentBySymbol(symbol.trim().toUpperCase(Locale.ROOT), limit)
                .map(IndicatorSnapshotRow::toDomain);
    }

    static int clampLimit(Integer requestedLimit) {
        if (requestedLimit == null || requestedLimit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(requestedLimit, MAX_LIMIT);
    }
}
