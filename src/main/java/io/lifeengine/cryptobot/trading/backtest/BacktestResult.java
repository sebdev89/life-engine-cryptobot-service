package io.lifeengine.cryptobot.trading.backtest;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * What one strategy did over one price series, net of every cost. {@code returnPct} and
 * {@code maxDrawdownPct} are percentages (12.5 = 12.5 %); {@code sharpe} is annualized from the per-bar
 * returns with a zero risk-free rate, {@code sortino} the same counting only losing bars as risk;
 * {@code worstMonthPct} is the worst UTC calendar month. {@code avgSolExposurePct} is the time-weighted share of
 * equity held in SOL and {@code returnOnExposurePct} the return per unit of that exposure, so a strategy that is
 * only partly in the market (DCA) can be compared with one that is fully in (hold SOL) on equal terms.
 * {@code costsUsd} is fees plus slippage paid on all trades.
 */
public record BacktestResult(
        String strategyId,
        Instant from,
        Instant to,
        int bars,
        BigDecimal initialUsd,
        BigDecimal finalUsd,
        BigDecimal pnlUsd,
        BigDecimal returnPct,
        BigDecimal maxDrawdownPct,
        BigDecimal sharpe,
        BigDecimal sortino,
        BigDecimal worstMonthPct,
        BigDecimal avgSolExposurePct,
        BigDecimal returnOnExposurePct,
        int trades,
        BigDecimal costsUsd,
        BigDecimal finalSol,
        BigDecimal finalUsdc) {}
