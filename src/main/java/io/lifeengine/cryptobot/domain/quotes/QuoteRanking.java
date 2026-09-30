package io.lifeengine.cryptobot.domain.quotes;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * "Con estos pesos, dónde conviene": ranks a {@link QuoteBoard} for one operation.
 *
 * <ul>
 *   <li>{@link Side#BUY} with {@code amount} ARS: {@code receives = amount / ask − withdrawalFee(network)},
 *       in units of the asset. Best first. When {@code network} is given but the exchange's fee for it
 *       is unknown, the row says so ({@code feeApplied=false}) instead of pretending it is zero.
 *   <li>{@link Side#SELL} with {@code amount} of the asset: {@code receives = amount × bid} in ARS. Best first.
 *   <li>No amount: BUY ranks by lowest ask, SELL by highest bid; {@code receives} is null.
 * </ul>
 *
 * Pure function, no I/O. The advisor gets this ranking as context; it never recomputes it.
 */
public final class QuoteRanking {

    public enum Side {
        BUY,
        SELL
    }

    private static final int ASSET_SCALE = 8;
    private static final int ARS_SCALE = 2;

    public record RankedQuote(
            int rank,
            String exchange,
            BigDecimal ask,
            BigDecimal bid,
            BigDecimal spreadPct,
            BigDecimal receives,
            String receivesUnit,
            BigDecimal effectivePrice,
            NetworkFee feeApplied,
            boolean feeKnown,
            boolean stale,
            String note) {}

    public record Ranking(String asset, Side side, BigDecimal amount, String network, List<RankedQuote> ranking) {}

    private QuoteRanking() {}

    public static Ranking rank(QuoteBoard board, Side side, BigDecimal amount, String network) {
        String net = network == null || network.isBlank() ? null : network.trim().toUpperCase();
        List<RankedQuote> rows = new ArrayList<>();
        for (ArsQuote q : board.quotes()) {
            rows.add(side == Side.BUY ? buyRow(board.asset(), q, amount, net) : sellRow(q, amount, net));
        }
        Comparator<RankedQuote> best = amount == null
                ? (side == Side.BUY ? Comparator.comparing(RankedQuote::ask) : Comparator.comparing(RankedQuote::bid).reversed())
                : Comparator.comparing(RankedQuote::receives, Comparator.nullsLast(Comparator.reverseOrder()));
        rows.sort(best.thenComparing(RankedQuote::exchange));
        List<RankedQuote> ranked = new ArrayList<>(rows.size());
        for (int i = 0; i < rows.size(); i++) {
            RankedQuote r = rows.get(i);
            ranked.add(new RankedQuote(i + 1, r.exchange(), r.ask(), r.bid(), r.spreadPct(), r.receives(), r.receivesUnit(),
                    r.effectivePrice(), r.feeApplied(), r.feeKnown(), r.stale(), r.note()));
        }
        return new Ranking(board.asset(), side, amount, net, List.copyOf(ranked));
    }

    private static RankedQuote buyRow(String asset, ArsQuote q, BigDecimal ars, String network) {
        NetworkFee fee = network == null ? null : q.feeFor(network);
        boolean feeKnown = network == null || fee != null;
        if (ars == null) {
            return new RankedQuote(0, q.exchange(), q.ask(), q.bid(), q.spreadPct(), null, asset, q.ask(), fee, feeKnown, q.stale(),
                    feeKnown ? null : "fee de retiro por " + network + " no publicada; no aplicada");
        }
        BigDecimal gross = ars.divide(q.ask(), ASSET_SCALE, RoundingMode.DOWN);
        BigDecimal net = fee == null ? gross : gross.subtract(fee.amount()).max(BigDecimal.ZERO);
        BigDecimal effective = net.signum() > 0 ? ars.divide(net, ARS_SCALE, RoundingMode.HALF_UP) : null;
        String note = null;
        if (!feeKnown) {
            note = "fee de retiro por " + network + " no publicada; no aplicada";
        } else if (fee != null && fee.source() == NetworkFee.FeeSource.CONFIGURED) {
            note = "fee de retiro configurada a mano (" + fee.amount().stripTrailingZeros().toPlainString() + " " + asset + "), no publicada por el exchange";
        }
        if (net.signum() == 0) {
            note = "la comisión de retiro consume todo el monto";
        }
        return new RankedQuote(0, q.exchange(), q.ask(), q.bid(), q.spreadPct(), net, asset, effective, fee, feeKnown, q.stale(), note);
    }

    private static RankedQuote sellRow(ArsQuote q, BigDecimal units, String network) {
        String note = network == null ? null : "vender no paga retiro; la red no cambia el ranking";
        if (units == null) {
            return new RankedQuote(0, q.exchange(), q.ask(), q.bid(), q.spreadPct(), null, "ARS", q.bid(), null, true, q.stale(), note);
        }
        BigDecimal ars = units.multiply(q.bid()).setScale(ARS_SCALE, RoundingMode.DOWN);
        return new RankedQuote(0, q.exchange(), q.ask(), q.bid(), q.spreadPct(), ars, "ARS", q.bid(), null, true, q.stale(), note);
    }
}
