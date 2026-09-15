package io.lifeengine.cryptobot.domain.quotes;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class QuoteRankingTest {

    private static final Instant AS_OF = Instant.parse("2026-09-15T18:00:00Z");

    private static ArsQuote q(String exchange, String ask, String bid, List<NetworkFee> fees) {
        return new ArsQuote(exchange, "USDT", new BigDecimal(ask), new BigDecimal(bid), fees, AS_OF, null);
    }

    private static final QuoteBoard BOARD = new QuoteBoard("USDT", List.of(
            q("bitso", "1510", "1490", List.of()),
            q("ripio", "1495", "1470", List.of(new NetworkFee("TRON", new BigDecimal("1"), NetworkFee.FeeSource.CONFIGURED))),
            q("buenbit", "1500", "1480", List.of(new NetworkFee("TRON", new BigDecimal("0"), NetworkFee.FeeSource.EXCHANGE_PUBLISHED)))),
            List.of(), AS_OF);

    @Test
    void buyWithPesos_ranksByUnitsReceived_lowestAskFirstWithoutNetwork() {
        QuoteRanking.Ranking r = QuoteRanking.rank(BOARD, QuoteRanking.Side.BUY, new BigDecimal("100000"), null);

        assertThat(r.ranking()).extracting(QuoteRanking.RankedQuote::exchange).containsExactly("ripio", "buenbit", "bitso");
        QuoteRanking.RankedQuote best = r.ranking().get(0);
        assertThat(best.rank()).isEqualTo(1);
        assertThat(best.receives()).isEqualByComparingTo("66.88963210"); // 100000 / 1495, 8 decimals, rounded down
        assertThat(best.receivesUnit()).isEqualTo("USDT");
        assertThat(best.effectivePrice()).isEqualByComparingTo("1495.00");
        assertThat(best.feeKnown()).isTrue();
        assertThat(best.feeApplied()).isNull();
        assertThat(best.spreadPct()).isEqualByComparingTo("1.6722");
    }

    @Test
    void buyWithNetwork_subtractsKnownFeeAndFlagsUnknownOnes() {
        QuoteRanking.Ranking r = QuoteRanking.rank(BOARD, QuoteRanking.Side.BUY, new BigDecimal("100000"), "tron");

        assertThat(r.network()).isEqualTo("TRON");
        // ripio: 66.8896 − 1 = 65.8896 · buenbit: 66.6666 − 0 · bitso: 66.2251 but fee unknown → not applied
        assertThat(r.ranking()).extracting(QuoteRanking.RankedQuote::exchange).containsExactly("buenbit", "bitso", "ripio");

        QuoteRanking.RankedQuote buenbit = r.ranking().get(0);
        assertThat(buenbit.receives()).isEqualByComparingTo("66.66666666");
        assertThat(buenbit.feeApplied().source()).isEqualTo(NetworkFee.FeeSource.EXCHANGE_PUBLISHED);
        assertThat(buenbit.note()).isNull();

        QuoteRanking.RankedQuote bitso = r.ranking().get(1);
        assertThat(bitso.feeKnown()).isFalse();
        assertThat(bitso.feeApplied()).isNull();
        assertThat(bitso.note()).contains("no publicada");

        QuoteRanking.RankedQuote ripio = r.ranking().get(2);
        assertThat(ripio.receives()).isEqualByComparingTo("65.88963210");
        assertThat(ripio.effectivePrice()).isEqualByComparingTo("1517.69"); // 100000 / 65.8896321
        assertThat(ripio.feeApplied().source()).isEqualTo(NetworkFee.FeeSource.CONFIGURED);
        assertThat(ripio.note()).contains("configurada a mano");
    }

    @Test
    void buyWhereFeeEatsEverything_isZeroNotNegative() {
        QuoteBoard tiny = new QuoteBoard("USDT", List.of(q("ripio", "1495", "1470", List.of(new NetworkFee("TRON", new BigDecimal("1"), NetworkFee.FeeSource.CONFIGURED)))), List.of(), AS_OF);
        QuoteRanking.Ranking r = QuoteRanking.rank(tiny, QuoteRanking.Side.BUY, new BigDecimal("1000"), "TRON");
        QuoteRanking.RankedQuote row = r.ranking().get(0);
        assertThat(row.receives()).isEqualByComparingTo("0");
        assertThat(row.effectivePrice()).isNull();
        assertThat(row.note()).contains("consume todo el monto");
    }

    @Test
    void sellUnits_ranksByPesosReceived_highestBidFirst() {
        QuoteRanking.Ranking r = QuoteRanking.rank(BOARD, QuoteRanking.Side.SELL, new BigDecimal("10"), "TRON");

        assertThat(r.ranking()).extracting(QuoteRanking.RankedQuote::exchange).containsExactly("bitso", "buenbit", "ripio");
        QuoteRanking.RankedQuote best = r.ranking().get(0);
        assertThat(best.receives()).isEqualByComparingTo("14900.00");
        assertThat(best.receivesUnit()).isEqualTo("ARS");
        assertThat(best.effectivePrice()).isEqualByComparingTo("1490");
        assertThat(best.note()).contains("vender no paga retiro");
    }

    @Test
    void noAmount_buyByAskSellByBid_receivesIsNull() {
        QuoteRanking.Ranking buy = QuoteRanking.rank(BOARD, QuoteRanking.Side.BUY, null, null);
        assertThat(buy.ranking()).extracting(QuoteRanking.RankedQuote::exchange).containsExactly("ripio", "buenbit", "bitso");
        assertThat(buy.ranking().get(0).receives()).isNull();

        QuoteRanking.Ranking sell = QuoteRanking.rank(BOARD, QuoteRanking.Side.SELL, null, null);
        assertThat(sell.ranking()).extracting(QuoteRanking.RankedQuote::exchange).containsExactly("bitso", "buenbit", "ripio");
    }

    @Test
    void staleQuotesKeepTheirFlagThroughTheRanking() {
        QuoteBoard b = new QuoteBoard("USDT", List.of(q("bitso", "1510", "1490", List.of()).asStale()), List.of(), AS_OF);
        assertThat(QuoteRanking.rank(b, QuoteRanking.Side.BUY, new BigDecimal("1000"), null).ranking().get(0).stale()).isTrue();
    }

    @Test
    void emptyBoardRanksNothing() {
        QuoteBoard empty = new QuoteBoard("BTC", List.of(), List.of(new QuoteBoard.Unavailable("bitso", QuoteBoard.Unavailable.Reason.FETCH_FAILED, "x")), AS_OF);
        assertThat(QuoteRanking.rank(empty, QuoteRanking.Side.BUY, new BigDecimal("1"), null).ranking()).isEmpty();
    }
}
