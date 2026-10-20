package io.lifeengine.cryptobot.application.paper;

import java.math.BigDecimal;
import java.time.Instant;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** (V16, KAN-1217): paper portfolios, their per-candle ticks and the CEX-vs-Jupiter price checks. */
public interface PaperRepository {

    Mono<Portfolio> find(String id);

    Flux<Portfolio> findAll();

    /**
     * The portfolio state after {@code tick} and the tick itself, in one transaction. {@code first} inserts the
     * portfolio; otherwise the update only applies if the stored {@code last_bar_time} is older than the tick's
     * (errors otherwise), so the same candle can never be applied twice.
     */
    Mono<Void> record(Portfolio after, Tick tick, boolean first);

    /** Oldest first. */
    Flux<Tick> ticks(String portfolioId);

    /** Idempotent per candle: a second check for the same bar_time is ignored. */
    Mono<Void> recordPriceCheck(PriceCheck check);

    /** Oldest first. */
    Flux<PriceCheck> priceChecks();

    record Portfolio(
            String id,
            String strategy,
            String params,
            int feeBps,
            int slippageBps,
            BigDecimal initialUsd,
            BigDecimal sol,
            BigDecimal usdc,
            int ticks,
            int trades,
            BigDecimal costsUsd,
            Instant startedAt,
            Instant lastBarTime) {}

    record Tick(
            String portfolioId,
            Instant barTime,
            BigDecimal close,
            BigDecimal notionalUsd,
            boolean traded,
            BigDecimal costUsd,
            BigDecimal sol,
            BigDecimal usdc,
            BigDecimal equityUsd,
            BigDecimal solWeight) {}

    record PriceCheck(Instant barTime, BigDecimal coinbaseSpot, BigDecimal jupiterPrice, Integer deviationBps, Instant observedAt) {}
}
