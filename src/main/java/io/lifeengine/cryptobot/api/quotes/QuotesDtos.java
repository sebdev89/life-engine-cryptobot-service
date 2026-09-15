package io.lifeengine.cryptobot.api.quotes;

import io.lifeengine.cryptobot.domain.quotes.NetworkFee;
import io.lifeengine.cryptobot.domain.quotes.QuoteBoard;
import io.lifeengine.cryptobot.domain.quotes.QuoteRanking;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** Wire shapes of {@code /api/cryptobot/quotes}. Same JSON the advisor receives as context. */
public final class QuotesDtos {

    private QuotesDtos() {}

    public record FeeView(String network, BigDecimal amount, String source) {
        static FeeView of(NetworkFee f) {
            return f == null ? null : new FeeView(f.network(), f.amount(), f.source().name());
        }
    }

    public record RankedView(
            int rank,
            String exchange,
            BigDecimal ask,
            BigDecimal bid,
            BigDecimal spreadPct,
            BigDecimal receives,
            String receivesUnit,
            BigDecimal effectivePrice,
            FeeView fee,
            boolean feeKnown,
            boolean stale,
            String note) {
        static RankedView of(QuoteRanking.RankedQuote r) {
            return new RankedView(r.rank(), r.exchange(), r.ask(), r.bid(), r.spreadPct(), r.receives(), r.receivesUnit(),
                    r.effectivePrice(), FeeView.of(r.feeApplied()), r.feeKnown(), r.stale(), r.note());
        }
    }

    public record UnavailableView(String exchange, String reason, String detail) {
        static UnavailableView of(QuoteBoard.Unavailable u) {
            return new UnavailableView(u.exchange(), u.reason().name(), u.detail());
        }
    }

    public record QuotesResponse(
            String asset,
            String side,
            BigDecimal amount,
            String amountUnit,
            String network,
            Instant generatedAt,
            List<RankedView> ranking,
            List<UnavailableView> unavailable,
            List<String> exchanges) {

        static QuotesResponse of(QuoteBoard board, QuoteRanking.Ranking ranking, List<String> exchanges) {
            String unit = ranking.side() == QuoteRanking.Side.BUY ? "ARS" : board.asset();
            return new QuotesResponse(
                    board.asset(),
                    ranking.side().name(),
                    ranking.amount(),
                    ranking.amount() == null ? null : unit,
                    ranking.network(),
                    board.generatedAt(),
                    ranking.ranking().stream().map(RankedView::of).toList(),
                    board.unavailable().stream().map(UnavailableView::of).toList(),
                    exchanges);
        }
    }
}
