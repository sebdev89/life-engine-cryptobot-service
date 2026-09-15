package io.lifeengine.cryptobot.application.quotes;

import static org.assertj.core.api.Assertions.assertThat;

import io.lifeengine.cryptobot.adapters.quotes.ExchangeQuoteSource;
import io.lifeengine.cryptobot.adapters.quotes.QuotesProperties;
import io.lifeengine.cryptobot.domain.quotes.ArsQuote;
import io.lifeengine.cryptobot.domain.quotes.QuoteBoard;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

class CachedArsQuotesServiceTest {

    /** A clock the test moves by hand. */
    static final class StepClock extends Clock {
        private Instant now = Instant.parse("2026-09-15T18:00:00Z");

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    /** Scripted exchange: counts fetches and answers whatever the supplier says. */
    static final class ScriptedSource implements ExchangeQuoteSource {
        final String id;
        final boolean enabled;
        final AtomicInteger fetches = new AtomicInteger();
        Supplier<Mono<List<ArsQuote>>> script;

        ScriptedSource(String id, boolean enabled, Supplier<Mono<List<ArsQuote>>> script) {
            this.id = id;
            this.enabled = enabled;
            this.script = script;
        }

        @Override
        public String exchange() {
            return id;
        }

        @Override
        public boolean enabled() {
            return enabled;
        }

        @Override
        public Mono<List<ArsQuote>> fetchArsQuotes() {
            fetches.incrementAndGet();
            return Mono.defer(script);
        }
    }

    private static final QuotesProperties PROPS = new QuotesProperties(Duration.ofSeconds(45), Duration.ofMinutes(5), Duration.ofSeconds(1), Map.of(), Map.of());

    private static ArsQuote q(String exchange, String asset, String ask, String bid) {
        return new ArsQuote(exchange, asset, new BigDecimal(ask), new BigDecimal(bid), List.of(), Instant.parse("2026-09-15T18:00:00Z"), null);
    }

    @Test
    void servesFromCacheWithinTtlAndRefreshesAfter() {
        StepClock clock = new StepClock();
        ScriptedSource bitso = new ScriptedSource("bitso", true, () -> Mono.just(List.of(q("bitso", "BTC", "150000000", "148500000"))));
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        CachedArsQuotesService service = new CachedArsQuotesService(List.of(bitso), PROPS, meters, clock);

        QuoteBoard first = service.board("btc").block();
        service.board("BTC").block();
        clock.advance(Duration.ofSeconds(30));
        service.board("BTC").block();
        assertThat(bitso.fetches).hasValue(1);
        assertThat(first.asset()).isEqualTo("BTC");
        assertThat(first.quotes()).hasSize(1);
        assertThat(first.quotes().get(0).stale()).isFalse();

        clock.advance(Duration.ofSeconds(16)); // 46 s > ttl
        service.board("BTC").block();
        assertThat(bitso.fetches).hasValue(2);

        assertThat(meters.counter(CachedArsQuotesService.METRIC_FETCH, "exchange", "bitso", "ok", "true").count()).isEqualTo(2.0);
        assertThat(meters.find(CachedArsQuotesService.METRIC_LATENCY).tag("exchange", "bitso").timer()).isNotNull();
        assertThat(meters.find(CachedArsQuotesService.METRIC_LATENCY).tag("exchange", "bitso").timer().count()).isEqualTo(2);
    }

    @Test
    void failedRefreshServesStaleUntilStaleMaxThenUnavailable() {
        StepClock clock = new StepClock();
        ScriptedSource ripio = new ScriptedSource("ripio", true, () -> Mono.just(List.of(q("ripio", "USDT", "1495", "1470"))));
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        CachedArsQuotesService service = new CachedArsQuotesService(List.of(ripio), PROPS, meters, clock);

        service.board("USDT").block();
        ripio.script = () -> Mono.error(new IllegalStateException("503 from upstream"));

        clock.advance(Duration.ofMinutes(1));
        QuoteBoard stale = service.board("USDT").block();
        assertThat(stale.quotes()).hasSize(1);
        assertThat(stale.quotes().get(0).stale()).isTrue();
        assertThat(stale.quotes().get(0).ask()).isEqualByComparingTo("1495");
        assertThat(stale.unavailable()).isEmpty();

        clock.advance(Duration.ofMinutes(5)); // 6 min since the last good fetch > stale-max
        QuoteBoard gone = service.board("USDT").block();
        assertThat(gone.quotes()).isEmpty();
        assertThat(gone.unavailable()).hasSize(1);
        assertThat(gone.unavailable().get(0).reason()).isEqualTo(QuoteBoard.Unavailable.Reason.FETCH_FAILED);
        assertThat(gone.unavailable().get(0).detail()).contains("503 from upstream");

        assertThat(meters.counter(CachedArsQuotesService.METRIC_FETCH, "exchange", "ripio", "ok", "false").count()).isEqualTo(2.0);
        assertThat(meters.counter(CachedArsQuotesService.METRIC_FETCH, "exchange", "ripio", "ok", "true").count()).isEqualTo(1.0);
    }

    @Test
    void oneExchangeDownNeverHidesTheOthers_andNotListedIsExplicit() {
        StepClock clock = new StepClock();
        ScriptedSource bitso = new ScriptedSource("bitso", true, () -> Mono.just(List.of(q("bitso", "BTC", "150000000", "148500000"))));
        ScriptedSource ripio = new ScriptedSource("ripio", true, () -> Mono.error(new RuntimeException("timeout")));
        ScriptedSource buenbit = new ScriptedSource("buenbit", true, () -> Mono.just(List.of(q("buenbit", "USDT", "1500", "1480"))));
        ScriptedSource lemon = new ScriptedSource("lemon", false, () -> Mono.error(new AssertionError("must not be called")));
        CachedArsQuotesService service = new CachedArsQuotesService(List.of(bitso, ripio, buenbit, lemon), PROPS, new SimpleMeterRegistry(), clock);

        QuoteBoard board = service.board("BTC").block();

        assertThat(board.quotes()).extracting(ArsQuote::exchange).containsExactly("bitso");
        assertThat(board.unavailable()).extracting(QuoteBoard.Unavailable::exchange).containsExactly("ripio", "buenbit", "lemon");
        assertThat(board.unavailable()).extracting(QuoteBoard.Unavailable::reason).containsExactly(
                QuoteBoard.Unavailable.Reason.FETCH_FAILED, QuoteBoard.Unavailable.Reason.NOT_LISTED, QuoteBoard.Unavailable.Reason.DISABLED);
        assertThat(lemon.fetches).hasValue(0);
        assertThat(service.exchanges()).containsExactly("bitso", "ripio", "buenbit", "lemon");
    }

    @Test
    void concurrentCallersShareOneInFlightFetch() {
        StepClock clock = new StepClock();
        ScriptedSource bitso = new ScriptedSource("bitso", true,
                () -> Mono.delay(Duration.ofMillis(200)).map(t -> List.of(q("bitso", "BTC", "150000000", "148500000"))));
        CachedArsQuotesService service = new CachedArsQuotesService(List.of(bitso), PROPS, new SimpleMeterRegistry(), clock);

        List<QuoteBoard> boards = Mono.zip(service.board("BTC"), service.board("BTC"), service.board("BTC"))
                .map(t -> List.of(t.getT1(), t.getT2(), t.getT3()))
                .block();

        assertThat(boards).allSatisfy(b -> assertThat(b.quotes()).hasSize(1));
        assertThat(bitso.fetches).hasValue(1);
    }
}
