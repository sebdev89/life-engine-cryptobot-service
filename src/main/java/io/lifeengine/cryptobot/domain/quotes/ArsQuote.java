package io.lifeengine.cryptobot.domain.quotes;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * One exchange's ARS quote for one crypto asset, normalised across exchanges (KAN-355).
 *
 * <p>Conventions: {@code ask} is what the user pays in ARS per 1 unit of {@code asset} when buying;
 * {@code bid} is what the user receives in ARS per unit when selling. {@code withdrawalFees} are
 * the exchange's per-network withdrawal fees in units of {@code asset}, only when known.
 * {@code stale} is true when the value comes from the cache after a failed refresh: still shown,
 * never silently passed off as live.
 */
public record ArsQuote(
        String exchange,
        String asset,
        BigDecimal ask,
        BigDecimal bid,
        List<NetworkFee> withdrawalFees,
        Instant asOf,
        String source,
        boolean stale) {

    public ArsQuote(String exchange, String asset, BigDecimal ask, BigDecimal bid, List<NetworkFee> withdrawalFees, Instant asOf, String source) {
        this(exchange, asset, ask, bid, withdrawalFees, asOf, source, false);
    }

    public ArsQuote {
        Objects.requireNonNull(exchange, "exchange");
        Objects.requireNonNull(asset, "asset");
        Objects.requireNonNull(ask, "ask");
        Objects.requireNonNull(bid, "bid");
        Objects.requireNonNull(asOf, "asOf");
        if (ask.signum() <= 0 || bid.signum() <= 0) {
            throw new IllegalArgumentException("ask and bid must be positive: " + exchange + " " + asset);
        }
        asset = asset.toUpperCase();
        withdrawalFees = withdrawalFees == null ? List.of() : List.copyOf(withdrawalFees);
        source = source == null ? exchange : source;
    }

    /** Spread as a percentage of the ask: {@code (ask - bid) / ask * 100}, 4 decimals. */
    public BigDecimal spreadPct() {
        return ask.subtract(bid).multiply(BigDecimal.valueOf(100)).divide(ask, 4, RoundingMode.HALF_UP);
    }

    /** Fee for withdrawing {@code asset} over {@code network}, if this quote knows it. */
    public NetworkFee feeFor(String network) {
        if (network == null) {
            return null;
        }
        String wanted = network.trim().toUpperCase();
        return withdrawalFees.stream().filter(f -> f.network().equalsIgnoreCase(wanted)).findFirst().orElse(null);
    }

    public ArsQuote withFees(List<NetworkFee> fees) {
        return new ArsQuote(exchange, asset, ask, bid, fees, asOf, source, stale);
    }

    public ArsQuote asStale() {
        return new ArsQuote(exchange, asset, ask, bid, withdrawalFees, asOf, source, true);
    }
}
