package io.lifeengine.cryptobot.application.controlplane;

import io.lifeengine.cryptobot.adapters.marketdata.TokenRegistry;
import io.lifeengine.cryptobot.trading.portfolio.PortfolioSnapshot;
import io.lifeengine.cryptobot.trading.portfolio.Position;
import io.lifeengine.cryptobot.trading.strategy.RebalanceIntent;
import io.lifeengine.cryptobot.trading.strategy.RebalanceLeg;
import io.lifeengine.cryptobot.trading.strategy.RebalancePlan;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Pure arithmetic: target weight → amount to sell (or buy) against the counter asset. The LLM
 * never touches these numbers; it only ever suggests a target weight.
 */
@Service
public class RebalancePlanner {

    private static final BigDecimal HUNDRED = new BigDecimal("100");
    /** Legs below this notional are dropped: not worth a fee, and a sign of rounding noise. */
    static final BigDecimal MIN_LEG_USD = new BigDecimal("0.50");

    private final TokenRegistry registry;

    public RebalancePlanner(TokenRegistry registry) {
        this.registry = registry;
    }

    public RebalancePlan plan(PortfolioSnapshot snapshot, RebalanceIntent intent) {
        BigDecimal total = snapshot.totalUsd() == null ? BigDecimal.ZERO : snapshot.totalUsd();
        if (total.signum() <= 0) {
            throw new ControlPlaneExceptions.InvalidRequest("EMPTY_PORTFOLIO", "Nothing to rebalance: portfolio has no priced value");
        }
        Map<String, BigDecimal> before = new LinkedHashMap<>();
        snapshot.positions().stream().filter(Position::priced).forEach(p -> before.put(p.symbol(), p.weightPct()));

        List<RebalanceLeg> legs = new ArrayList<>();
        Map<String, BigDecimal> after = new LinkedHashMap<>(before);
        BigDecimal counterDeltaUsd = BigDecimal.ZERO;

        for (Map.Entry<String, BigDecimal> target : intent.targetWeights().entrySet()) {
            String symbol = target.getKey().trim().toUpperCase();
            BigDecimal targetPct = target.getValue();
            if (targetPct == null || targetPct.signum() < 0 || targetPct.compareTo(HUNDRED) > 0) {
                throw new ControlPlaneExceptions.InvalidRequest("INVALID_TARGET", "Target weight for " + symbol + " must be within 0..100");
            }
            if (symbol.equals(intent.counterAsset())) {
                throw new ControlPlaneExceptions.InvalidRequest("INVALID_TARGET", "Cannot target the counter asset " + symbol);
            }
            Optional<Position> pos = snapshot.position(symbol).filter(Position::priced);
            BigDecimal currentPct = pos.map(Position::weightPct).orElse(BigDecimal.ZERO);
            BigDecimal targetUsd = total.multiply(targetPct).divide(HUNDRED, 6, RoundingMode.HALF_UP);
            BigDecimal currentUsd = pos.map(Position::valueUsd).orElse(BigDecimal.ZERO);
            BigDecimal deltaUsd = currentUsd.subtract(targetUsd); // > 0 → sell, < 0 → buy
            if (deltaUsd.abs().compareTo(MIN_LEG_USD) < 0) {
                continue;
            }
            BigDecimal price = pos.map(Position::priceUsd)
                    .orElseThrow(() -> new ControlPlaneExceptions.InvalidRequest("UNPRICED_ASSET", symbol + " is not held or has no price; cannot plan a leg for it"));
            String mint = pos.map(Position::mint).orElse(registry.mintOfSymbol(symbol).orElse(null));
            BigDecimal amount = deltaUsd.abs().divide(price, 9, RoundingMode.DOWN);
            RebalanceLeg.Action action = deltaUsd.signum() > 0 ? RebalanceLeg.Action.SELL : RebalanceLeg.Action.BUY;
            legs.add(new RebalanceLeg(action, symbol, mint, amount, deltaUsd.abs().setScale(2, RoundingMode.HALF_UP),
                    currentPct, targetPct, intent.counterAsset()));
            after.put(symbol, targetPct);
            counterDeltaUsd = counterDeltaUsd.add(deltaUsd);
        }

        // Counter asset absorbs the net notional (sells add, buys subtract). Total value is unchanged
        // in a paper rebalance, so weights are recomputed on the same base.
        BigDecimal counterBefore = before.getOrDefault(intent.counterAsset(), BigDecimal.ZERO);
        BigDecimal counterAfter = counterBefore.add(counterDeltaUsd.multiply(HUNDRED).divide(total, 4, RoundingMode.HALF_UP));
        if (counterAfter.signum() < 0) {
            throw new ControlPlaneExceptions.InvalidRequest("INSUFFICIENT_COUNTER_ASSET",
                    "Not enough " + intent.counterAsset() + " to fund the buys (would need " + counterAfter.abs().setScale(1, RoundingMode.HALF_UP) + "% more)");
        }
        after.put(intent.counterAsset(), counterAfter.setScale(4, RoundingMode.HALF_UP));

        BigDecimal turnover = legs.stream().map(RebalanceLeg::estimatedUsd).reduce(BigDecimal.ZERO, BigDecimal::add);
        String summary = legs.isEmpty()
                ? "Portfolio already within target; no trade needed."
                : legs.stream()
                        .map(l -> l.action() + " " + l.amount().setScale(4, RoundingMode.DOWN).stripTrailingZeros().toPlainString()
                                + " " + l.symbol() + " (≈$" + l.estimatedUsd() + ") → " + l.symbol() + " "
                                + l.weightPctBefore().setScale(1, RoundingMode.HALF_UP) + "% → " + l.weightPctAfter().setScale(1, RoundingMode.HALF_UP) + "%")
                        .reduce((a, b) -> a + "; " + b)
                        .orElse("");
        return new RebalancePlan(legs, total, before, after, turnover, summary);
    }

    /** Applies the plan to a snapshot to produce the expected post-trade positions (for risk-after). */
    public PortfolioSnapshot project(PortfolioSnapshot snapshot, RebalancePlan plan) {
        Map<String, Position> bySymbol = new LinkedHashMap<>();
        snapshot.positions().forEach(p -> bySymbol.put(p.symbol(), p));
        BigDecimal counterDelta = BigDecimal.ZERO;
        String counter = null;
        for (RebalanceLeg leg : plan.legs()) {
            counter = leg.counterAsset();
            Position p = bySymbol.get(leg.symbol());
            if (p == null) {
                continue;
            }
            BigDecimal newAmount = leg.action() == RebalanceLeg.Action.SELL ? p.amount().subtract(leg.amount()) : p.amount().add(leg.amount());
            BigDecimal newValue = newAmount.multiply(p.priceUsd()).setScale(6, RoundingMode.HALF_UP);
            bySymbol.put(leg.symbol(), new Position(p.mint(), p.symbol(), newAmount, p.decimals(), p.priceUsd(), newValue, null, p.stable(), p.nativeSol(), p.priceSource()));
            counterDelta = leg.action() == RebalanceLeg.Action.SELL ? counterDelta.add(leg.estimatedUsd()) : counterDelta.subtract(leg.estimatedUsd());
        }
        if (counter != null && counterDelta.signum() != 0) {
            Position c = bySymbol.get(counter);
            String mint = registry.mintOfSymbol(counter).orElse(counter);
            BigDecimal price = c == null ? BigDecimal.ONE : c.priceUsd();
            BigDecimal value = (c == null ? BigDecimal.ZERO : c.valueUsd()).add(counterDelta);
            BigDecimal amount = value.divide(price, 6, RoundingMode.HALF_UP);
            bySymbol.put(counter, new Position(mint, counter, amount, c == null ? 6 : c.decimals(), price, value, null, true, false, c == null ? "projected" : c.priceSource()));
        }
        BigDecimal total = bySymbol.values().stream().filter(Position::priced).map(Position::valueUsd).reduce(BigDecimal.ZERO, BigDecimal::add);
        List<Position> weighted = bySymbol.values().stream()
                .map(p -> new Position(p.mint(), p.symbol(), p.amount(), p.decimals(), p.priceUsd(), p.valueUsd(),
                        p.valueUsd() == null || total.signum() == 0 ? null : p.valueUsd().multiply(HUNDRED).divide(total, 4, RoundingMode.HALF_UP),
                        p.stable(), p.nativeSol(), p.priceSource()))
                .toList();
        return new PortfolioSnapshot(snapshot.id(), snapshot.walletId(), snapshot.capturedAt(), total, weighted, snapshot.priceSource() + "+projected", snapshot.recentTxCount());
    }
}
