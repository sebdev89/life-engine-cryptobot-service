package io.lifeengine.cryptobot.infrastructure.persistence.r2dbc;

import io.lifeengine.cryptobot.domain.MarketReviewRun;
import java.util.UUID;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Reactive persistence port for {@code market_review_run}. Defined as a plain interface (not a
 * {@code ReactiveCrudRepository}) so the {@code metadata_json} JSONB column can be mapped
 * explicitly via {@code DatabaseClient} + {@code io.r2dbc.postgresql.codec.Json} — mirrors the
 * pattern used in {@code life-engine-runtime}'s {@code R2dbcRunStore}.
 */
public interface MarketReviewRunRepository {

    /** Inserts a brand-new row. Returns the saved row (with normalised timestamps). */
    Mono<MarketReviewRun> insert(MarketReviewRun run);

    /** Persists the full row state (used by reconciliation after a runtime poll). */
    Mono<MarketReviewRun> update(MarketReviewRun run);

    Mono<MarketReviewRun> findById(UUID id);

    Mono<MarketReviewRun> findByRuntimeRunId(UUID runtimeRunId);

    /** Latest run for one symbol, ordered by {@code started_at DESC}. Empty if none. */
    Mono<MarketReviewRun> findLatestBySymbol(String symbol);

    /** History for one symbol, ordered by {@code started_at DESC}, hard-capped by the caller. */
    Flux<MarketReviewRun> findRecentBySymbol(String symbol, int limit);
}
