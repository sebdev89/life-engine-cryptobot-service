package io.lifeengine.cryptobot.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.lifeengine.cryptobot.domain.WatchlistEntry;
import io.lifeengine.cryptobot.infrastructure.persistence.r2dbc.WatchlistEntryRepository;
import io.lifeengine.cryptobot.infrastructure.persistence.r2dbc.WatchlistEntryRow;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class WatchlistServiceTest {

    @Test
    void listActive_returnsRowsConverted() {
        WatchlistEntryRow row = row("BTCUSDT", "BINANCE");
        WatchlistEntryRepository repo = mock(WatchlistEntryRepository.class);
        when(repo.findAllActiveOrdered()).thenReturn(Flux.just(row));

        WatchlistService service = new WatchlistService(repo);

        StepVerifier.create(service.listActive())
                .expectNextMatches(e -> e.symbol().equals("BTCUSDT") && e.exchange().equals("BINANCE"))
                .verifyComplete();
    }

    @Test
    void findBySymbol_normalisesAndDelegates() {
        WatchlistEntryRepository repo = mock(WatchlistEntryRepository.class);
        when(repo.findBySymbol("BTCUSDT")).thenReturn(Flux.just(row("BTCUSDT", "BINANCE")));

        WatchlistService service = new WatchlistService(repo);

        StepVerifier.create(service.findBySymbol(" btcusdt "))
                .expectNextCount(1)
                .verifyComplete();
    }

    @Test
    void findBySymbol_emptyWhenSymbolBlank() {
        WatchlistService service = new WatchlistService(mock(WatchlistEntryRepository.class));
        StepVerifier.create(service.findBySymbol(" ")).verifyComplete();
    }

    @Test
    void create_seedsIdAndTimestamps() {
        WatchlistEntryRepository repo = mock(WatchlistEntryRepository.class);
        when(repo.save(any(WatchlistEntryRow.class)))
                .thenAnswer(inv -> Mono.just(inv.getArgument(0, WatchlistEntryRow.class)));

        WatchlistEntry input = new WatchlistEntry(
                null, "btcusdt", "Bitcoin", "CRYPTO", "CRYPTO_CORE", "BINANCE", 100, true, null, null, null);
        WatchlistService service = new WatchlistService(repo);

        StepVerifier.create(service.create(input))
                .expectNextMatches(saved ->
                        saved.id() != null
                                && saved.symbol().equals("BTCUSDT")
                                && saved.createdAt() != null
                                && saved.updatedAt() != null)
                .verifyComplete();
    }

    private static WatchlistEntryRow row(String symbol, String exchange) {
        WatchlistEntryRow row = new WatchlistEntryRow();
        row.setId(UUID.randomUUID());
        row.setSymbol(symbol);
        row.setExchange(exchange);
        row.setAssetType("CRYPTO");
        row.setSectorTheme("CRYPTO_CORE");
        row.setPriority(100);
        row.setActive(true);
        row.setDisplayName("Bitcoin");
        row.setCreatedAt(Instant.now());
        row.setUpdatedAt(Instant.now());
        return row;
    }
}
