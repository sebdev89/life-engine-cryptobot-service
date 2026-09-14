package io.lifeengine.cryptobot.infrastructure.persistence.controlplane;

import io.lifeengine.cryptobot.domain.portfolio.PortfolioSnapshot;
import io.r2dbc.postgresql.codec.Json;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Profile("!test")
@Component
public class PortfolioSnapshotR2dbcStore implements PortfolioSnapshotRepository {

    private final DatabaseClient db;
    private final JsonDocs docs;

    public PortfolioSnapshotR2dbcStore(DatabaseClient db, JsonDocs docs) {
        this.db = db;
        this.docs = docs;
    }

    @Override
    public Mono<PortfolioSnapshot> insert(PortfolioSnapshot s) {
        return db.sql("INSERT INTO portfolio_snapshot (id, wallet_id, captured_at, total_usd, doc)"
                        + " VALUES (:id, :wallet, :captured, :total, :doc)")
                .bind("id", s.id())
                .bind("wallet", s.walletId())
                .bind("captured", s.capturedAt())
                .bind("total", s.totalUsd())
                .bind("doc", docs.write(s))
                .fetch()
                .rowsUpdated()
                .then(findById(s.id()));
    }

    @Override
    public Mono<PortfolioSnapshot> findById(UUID id) {
        return db.sql("SELECT doc FROM portfolio_snapshot WHERE id = :id")
                .bind("id", id)
                .map((row, meta) -> docs.read(row.get("doc", Json.class), PortfolioSnapshot.class))
                .one();
    }

    @Override
    public Flux<PortfolioSnapshot> findRecent(UUID walletId, int limit) {
        return db.sql("SELECT doc FROM portfolio_snapshot WHERE wallet_id = :wallet ORDER BY captured_at DESC LIMIT :limit")
                .bind("wallet", walletId)
                .bind("limit", Math.max(1, Math.min(limit, 100)))
                .map((row, meta) -> docs.read(row.get("doc", Json.class), PortfolioSnapshot.class))
                .all();
    }
}
