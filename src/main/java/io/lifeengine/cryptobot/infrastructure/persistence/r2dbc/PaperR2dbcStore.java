package io.lifeengine.cryptobot.infrastructure.persistence.r2dbc;

import io.lifeengine.cryptobot.application.paper.PaperRepository;
import io.r2dbc.spi.Row;
import java.math.BigDecimal;
import java.time.Instant;
import org.springframework.context.annotation.Profile;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** (V16, KAN-1217): {@code paper_portfolio}, {@code paper_tick} and {@code paper_price_check}. */
@Profile("!test")
@Component
public class PaperR2dbcStore implements PaperRepository {

    private static final String PORTFOLIO_COLS = "id, strategy, params, fee_bps, slippage_bps, initial_usd, sol, usdc, ticks, trades, costs_usd,"
            + " started_at, last_bar_time";
    private static final String TICK_COLS = "portfolio_id, bar_time, close, notional_usd, traded, cost_usd, sol, usdc, equity_usd, sol_weight";

    private final DatabaseClient db;
    private final TransactionalOperator tx;

    public PaperR2dbcStore(DatabaseClient db, TransactionalOperator tx) {
        this.db = db;
        this.tx = tx;
    }

    @Override
    public Mono<Portfolio> find(String id) {
        return db.sql("SELECT " + PORTFOLIO_COLS + " FROM paper_portfolio WHERE id = :id").bind("id", id)
                .map((row, meta) -> portfolio(row)).one();
    }

    @Override
    public Flux<Portfolio> findAll() {
        return db.sql("SELECT " + PORTFOLIO_COLS + " FROM paper_portfolio ORDER BY id").map((row, meta) -> portfolio(row)).all();
    }

    @Override
    public Mono<Void> record(Portfolio p, Tick t, boolean first) {
        Mono<Long> head = first
                ? db.sql("INSERT INTO paper_portfolio (" + PORTFOLIO_COLS + ") VALUES (:id, :strategy, :params, :fee, :slip, :initial, :sol, :usdc,"
                                + " :ticks, :trades, :costs, :started, :last)")
                        .bind("id", p.id()).bind("strategy", p.strategy()).bind("params", p.params()).bind("fee", p.feeBps())
                        .bind("slip", p.slippageBps()).bind("initial", p.initialUsd()).bind("sol", p.sol()).bind("usdc", p.usdc())
                        .bind("ticks", p.ticks()).bind("trades", p.trades()).bind("costs", p.costsUsd()).bind("started", p.startedAt())
                        .bind("last", p.lastBarTime())
                        .fetch().rowsUpdated()
                : db.sql("UPDATE paper_portfolio SET sol = :sol, usdc = :usdc, ticks = :ticks, trades = :trades, costs_usd = :costs,"
                                + " last_bar_time = :last, updated_at = NOW() WHERE id = :id AND last_bar_time < :last")
                        .bind("id", p.id()).bind("sol", p.sol()).bind("usdc", p.usdc()).bind("ticks", p.ticks()).bind("trades", p.trades())
                        .bind("costs", p.costsUsd()).bind("last", p.lastBarTime())
                        .fetch().rowsUpdated()
                        .flatMap(n -> n == 1 ? Mono.just(n) : Mono.error(new IllegalStateException(
                                "paper portfolio " + p.id() + " already at or past " + p.lastBarTime())));
        Mono<Long> tick = db.sql("INSERT INTO paper_tick (" + TICK_COLS + ") VALUES (:portfolio, :bar, :close, :notional, :traded, :cost, :sol, :usdc,"
                        + " :equity, :weight)")
                .bind("portfolio", t.portfolioId()).bind("bar", t.barTime()).bind("close", t.close()).bind("notional", t.notionalUsd())
                .bind("traded", t.traded()).bind("cost", t.costUsd()).bind("sol", t.sol()).bind("usdc", t.usdc()).bind("equity", t.equityUsd())
                .bind("weight", t.solWeight())
                .fetch().rowsUpdated();
        return tx.transactional(head.then(tick)).then();
    }

    @Override
    public Flux<Tick> ticks(String portfolioId) {
        return db.sql("SELECT " + TICK_COLS + " FROM paper_tick WHERE portfolio_id = :id ORDER BY bar_time").bind("id", portfolioId)
                .map((row, meta) -> new Tick(
                        row.get("portfolio_id", String.class),
                        row.get("bar_time", Instant.class),
                        row.get("close", BigDecimal.class),
                        row.get("notional_usd", BigDecimal.class),
                        Boolean.TRUE.equals(row.get("traded", Boolean.class)),
                        row.get("cost_usd", BigDecimal.class),
                        row.get("sol", BigDecimal.class),
                        row.get("usdc", BigDecimal.class),
                        row.get("equity_usd", BigDecimal.class),
                        row.get("sol_weight", BigDecimal.class)))
                .all();
    }

    @Override
    public Mono<Void> recordPriceCheck(PriceCheck c) {
        DatabaseClient.GenericExecuteSpec s = db.sql("INSERT INTO paper_price_check (bar_time, coinbase_spot, jupiter_price, deviation_bps, observed_at)"
                        + " VALUES (:bar, :cb, :jup, :dev, :at) ON CONFLICT (bar_time) DO NOTHING")
                .bind("bar", c.barTime()).bind("at", c.observedAt());
        s = c.coinbaseSpot() == null ? s.bindNull("cb", BigDecimal.class) : s.bind("cb", c.coinbaseSpot());
        s = c.jupiterPrice() == null ? s.bindNull("jup", BigDecimal.class) : s.bind("jup", c.jupiterPrice());
        s = c.deviationBps() == null ? s.bindNull("dev", Integer.class) : s.bind("dev", c.deviationBps());
        return s.fetch().rowsUpdated().then();
    }

    @Override
    public Flux<PriceCheck> priceChecks() {
        return db.sql("SELECT bar_time, coinbase_spot, jupiter_price, deviation_bps, observed_at FROM paper_price_check ORDER BY bar_time")
                .map((row, meta) -> new PriceCheck(
                        row.get("bar_time", Instant.class),
                        row.get("coinbase_spot", BigDecimal.class),
                        row.get("jupiter_price", BigDecimal.class),
                        row.get("deviation_bps", Integer.class),
                        row.get("observed_at", Instant.class)))
                .all();
    }

    private static Portfolio portfolio(Row row) {
        return new Portfolio(
                row.get("id", String.class),
                row.get("strategy", String.class),
                row.get("params", String.class),
                row.get("fee_bps", Integer.class),
                row.get("slippage_bps", Integer.class),
                row.get("initial_usd", BigDecimal.class),
                row.get("sol", BigDecimal.class),
                row.get("usdc", BigDecimal.class),
                row.get("ticks", Integer.class),
                row.get("trades", Integer.class),
                row.get("costs_usd", BigDecimal.class),
                row.get("started_at", Instant.class),
                row.get("last_bar_time", Instant.class));
    }
}
