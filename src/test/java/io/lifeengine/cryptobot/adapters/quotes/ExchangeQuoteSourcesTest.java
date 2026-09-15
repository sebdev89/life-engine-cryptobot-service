package io.lifeengine.cryptobot.adapters.quotes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.lifeengine.cryptobot.domain.quotes.ArsQuote;
import io.lifeengine.cryptobot.domain.quotes.NetworkFee;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.test.StepVerifier;

/**
 * One fixture per exchange, parsed offline; plus one round-trip per adapter through a local
 * MockWebServer to pin the path each adapter calls. No exchange is contacted.
 */
class ExchangeQuoteSourcesTest {

    private static final Instant NOW = Instant.parse("2026-09-15T18:05:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final ObjectMapper JSON = new ObjectMapper();
    private MockWebServer server;

    // One server per test: takeRequest() must never see another test's traffic.
    @BeforeEach
    void start() throws Exception {
        server = new MockWebServer();
        server.start();
    }

    @AfterEach
    void stop() throws Exception {
        server.shutdown();
    }

    private String base() {
        return "http://localhost:" + server.getPort();
    }

    // ---------------------------------------------------------------- Bitso

    @Test
    void bitso_parsesOnlyArsBooksWithBothSides() {
        BitsoQuoteSource bitso = new BitsoQuoteSource(WebClient.builder(), Fixtures.pointingAt(base()), JSON, CLOCK);
        List<ArsQuote> quotes = bitso.parseDocument(Fixtures.read("bitso-ticker.json"), NOW);

        assertThat(quotes).extracting(ArsQuote::asset).containsExactly("BTC", "USDT"); // eth_ars has null sides, btc_mxn is not ARS
        ArsQuote btc = quotes.get(0);
        assertThat(btc.exchange()).isEqualTo("bitso");
        assertThat(btc.ask()).isEqualByComparingTo("150000000.00");
        assertThat(btc.bid()).isEqualByComparingTo("148500000.00");
        assertThat(btc.asOf()).isEqualTo(Instant.parse("2026-09-15T18:00:00Z")); // created_at wins over the clock
        assertThat(btc.spreadPct()).isEqualByComparingTo("1.0000");
        assertThat(btc.withdrawalFees()).isEmpty();
        assertThat(btc.stale()).isFalse();
    }

    @Test
    void bitso_successFalseIsAnError() {
        BitsoQuoteSource bitso = new BitsoQuoteSource(WebClient.builder(), Fixtures.pointingAt(base()), JSON, CLOCK);
        assertThatThrownBy(() -> bitso.parseDocument("{\"success\":false,\"error\":{\"code\":\"0301\",\"message\":\"Unknown Order Book\"}}", NOW))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unknown Order Book");
    }

    @Test
    void bitso_callsDocumentedTickerPath() throws Exception {
        server.enqueue(new MockResponse().setHeader("Content-Type", "application/json").setBody(Fixtures.read("bitso-ticker.json")));
        BitsoQuoteSource bitso = new BitsoQuoteSource(WebClient.builder(), Fixtures.pointingAt(base()), JSON, CLOCK);

        StepVerifier.create(bitso.fetchArsQuotes()).assertNext(q -> assertThat(q).hasSize(2)).verifyComplete();
        RecordedRequest req = server.takeRequest();
        assertThat(req.getMethod()).isEqualTo("GET");
        assertThat(req.getPath()).isEqualTo("/v3/ticker/");
        assertThat(req.getHeader("Authorization")).isNull(); // public endpoint, never a key
    }

    // ---------------------------------------------------------------- Ripio

    @Test
    void ripio_mapsBuyRateToAskAndSellRateToBid() {
        RipioQuoteSource ripio = new RipioQuoteSource(WebClient.builder(), Fixtures.pointingAt(base()), JSON, CLOCK);
        List<ArsQuote> quotes = ripio.parseDocument(Fixtures.read("ripio-rates.json"), NOW);

        assertThat(quotes).extracting(ArsQuote::asset).containsExactly("BTC", "USDT", "USDC"); // BTC_USDC skipped
        ArsQuote usdt = quotes.get(1);
        assertThat(usdt.exchange()).isEqualTo("ripio");
        assertThat(usdt.ask()).isEqualByComparingTo("1495.00");
        assertThat(usdt.bid()).isEqualByComparingTo("1470.00");
        assertThat(usdt.asOf()).isEqualTo(NOW); // feed has no timestamp → clock
    }

    @Test
    void ripio_acceptsResultsWrapperAndTickerOnlyRows() {
        RipioQuoteSource ripio = new RipioQuoteSource(WebClient.builder(), Fixtures.pointingAt(base()), JSON, CLOCK);
        List<ArsQuote> quotes = ripio.parseDocument(
                "{\"results\":[{\"ticker\":\"SOL_ARS\",\"buy_rate\":\"210000\",\"sell_rate\":\"205000\"},{\"ticker\":\"SOL_USDC\",\"buy_rate\":\"1\",\"sell_rate\":\"1\"}]}", NOW);
        assertThat(quotes).hasSize(1);
        assertThat(quotes.get(0).asset()).isEqualTo("SOL");
    }

    @Test
    void ripio_callsRatesForArgentina() throws Exception {
        server.enqueue(new MockResponse().setHeader("Content-Type", "application/json").setBody(Fixtures.read("ripio-rates.json")));
        RipioQuoteSource ripio = new RipioQuoteSource(WebClient.builder(), Fixtures.pointingAt(base()), JSON, CLOCK);

        StepVerifier.create(ripio.fetchArsQuotes()).assertNext(q -> assertThat(q).hasSize(3)).verifyComplete();
        assertThat(server.takeRequest().getPath()).isEqualTo("/api/v3/rates/?country=AR");
    }

    // ---------------------------------------------------------------- Buenbit

    @Test
    void buenbit_mapsPurchaseToAskAndSellingToBid_skippingZeroAndNonArs() {
        BuenbitQuoteSource buenbit = new BuenbitQuoteSource(WebClient.builder(), Fixtures.pointingAt(base()), JSON, CLOCK);
        List<ArsQuote> quotes = buenbit.parseDocument(Fixtures.read("buenbit-tickers.json"), NOW);

        assertThat(quotes).extracting(ArsQuote::asset).containsExactly("BTC", "USDT", "DAI"); // btcusdt not ARS, ethars is 0/0
        ArsQuote btc = quotes.get(0);
        assertThat(btc.exchange()).isEqualTo("buenbit");
        assertThat(btc.ask()).isEqualByComparingTo("149500000.00");
        assertThat(btc.bid()).isEqualByComparingTo("148900000.00");
    }

    @Test
    void buenbit_fallsBackToMarketKeyWhenCurrencyFieldsAreMissing() {
        BuenbitQuoteSource buenbit = new BuenbitQuoteSource(WebClient.builder(), Fixtures.pointingAt(base()), JSON, CLOCK);
        List<ArsQuote> quotes = buenbit.parseDocument(
                "{\"object\":{\"solars\":{\"purchase_price\":\"210000\",\"selling_price\":\"205000\"},\"solusdt\":{\"purchase_price\":\"1\",\"selling_price\":\"1\"}}}", NOW);
        assertThat(quotes).hasSize(1);
        assertThat(quotes.get(0).asset()).isEqualTo("SOL");
    }

    @Test
    void buenbit_callsMarketTickers() throws Exception {
        server.enqueue(new MockResponse().setHeader("Content-Type", "application/json").setBody(Fixtures.read("buenbit-tickers.json")));
        BuenbitQuoteSource buenbit = new BuenbitQuoteSource(WebClient.builder(), Fixtures.pointingAt(base()), JSON, CLOCK);

        StepVerifier.create(buenbit.fetchArsQuotes()).assertNext(q -> assertThat(q).hasSize(3)).verifyComplete();
        assertThat(server.takeRequest().getPath()).isEqualTo("/api/market/tickers/");
    }

    // ---------------------------------------------------------------- shared behaviour

    @Test
    void configuredWithdrawalFeesAreMergedAndLabelledConfigured() {
        server.enqueue(new MockResponse().setHeader("Content-Type", "application/json").setBody(Fixtures.read("ripio-rates.json")));
        QuotesProperties props = Fixtures.pointingAt(base(), Map.of("ripio", Map.of("USDT", Map.of("TRON", new BigDecimal("1"), "bsc", new BigDecimal("0.5")))));
        RipioQuoteSource ripio = new RipioQuoteSource(WebClient.builder(), props, JSON, CLOCK);

        StepVerifier.create(ripio.fetchArsQuotes())
                .assertNext(quotes -> {
                    ArsQuote usdt = quotes.stream().filter(q -> q.asset().equals("USDT")).findFirst().orElseThrow();
                    assertThat(usdt.withdrawalFees()).hasSize(2);
                    NetworkFee tron = usdt.feeFor("tron");
                    assertThat(tron.amount()).isEqualByComparingTo("1");
                    assertThat(tron.source()).isEqualTo(NetworkFee.FeeSource.CONFIGURED);
                    assertThat(usdt.feeFor("BSC").network()).isEqualTo("BSC"); // normalised upper-case
                    assertThat(usdt.feeFor("LN")).isNull();
                    ArsQuote btc = quotes.stream().filter(q -> q.asset().equals("BTC")).findFirst().orElseThrow();
                    assertThat(btc.withdrawalFees()).isEmpty();
                })
                .verifyComplete();
    }

    @Test
    void httpErrorPropagatesSoTheCacheCanDecide() {
        server.enqueue(new MockResponse().setResponseCode(503));
        BitsoQuoteSource bitso = new BitsoQuoteSource(WebClient.builder(), Fixtures.pointingAt(base()), JSON, CLOCK);
        StepVerifier.create(bitso.fetchArsQuotes()).expectError().verify();
    }

    @Test
    void garbageBodyIsAnError() {
        server.enqueue(new MockResponse().setHeader("Content-Type", "text/html").setBody("<html>cloudflare</html>"));
        BuenbitQuoteSource buenbit = new BuenbitQuoteSource(WebClient.builder(), Fixtures.pointingAt(base()), JSON, CLOCK);
        StepVerifier.create(buenbit.fetchArsQuotes()).expectErrorSatisfies(ex -> assertThat(ex).hasMessageContaining("unparseable")).verify();
    }

    @Test
    void disabledExchangeIsReportedByTheFlag() {
        QuotesProperties props = new QuotesProperties(null, null, null, Map.of("bitso", new QuotesProperties.Exchange(false, null)), null);
        BitsoQuoteSource bitso = new BitsoQuoteSource(WebClient.builder(), props, JSON, CLOCK);
        assertThat(bitso.enabled()).isFalse();
        assertThat(new RipioQuoteSource(WebClient.builder(), props, JSON, CLOCK).enabled()).isTrue(); // default on
        assertThat(props.exchange("ripio", "https://x").baseUrl()).isEqualTo("https://x");
    }
}
