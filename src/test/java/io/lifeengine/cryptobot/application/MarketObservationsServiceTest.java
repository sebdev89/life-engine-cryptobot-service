package io.lifeengine.cryptobot.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.lifeengine.cryptobot.infrastructure.persistence.r2dbc.MarketObservationRepository;
import io.lifeengine.cryptobot.infrastructure.persistence.r2dbc.MarketObservationRow;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

class MarketObservationsServiceTest {

    @Test
    void clampLimit_appliesDefaultsAndCap() {
        assertThat(MarketObservationsService.clampLimit(null)).isEqualTo(20);
        assertThat(MarketObservationsService.clampLimit(0)).isEqualTo(20);
        assertThat(MarketObservationsService.clampLimit(-5)).isEqualTo(20);
        assertThat(MarketObservationsService.clampLimit(50)).isEqualTo(50);
        assertThat(MarketObservationsService.clampLimit(5000)).isEqualTo(200);
    }

    @Test
    void findRecentBySymbol_emptyWhenBlank() {
        MarketObservationsService service = new MarketObservationsService(mock(MarketObservationRepository.class));
        StepVerifier.create(service.findRecentBySymbol("", 10)).verifyComplete();
    }

    @Test
    void findRecentBySymbol_normalisesAndDelegates() {
        MarketObservationRepository repo = mock(MarketObservationRepository.class);
        MarketObservationRow row = new MarketObservationRow();
        row.setId(UUID.randomUUID());
        row.setSymbol("BTCUSDT");
        row.setVenue("BINANCE");
        row.setObservedAt(Instant.now());
        row.setLastPrice(new BigDecimal("67800.0"));
        row.setCreatedAt(Instant.now());
        when(repo.findRecentBySymbol(eq("BTCUSDT"), eq(20))).thenReturn(Flux.just(row));

        MarketObservationsService service = new MarketObservationsService(repo);
        StepVerifier.create(service.findRecentBySymbol(" btcusdt ", null))
                .expectNextMatches(o -> o.symbol().equals("BTCUSDT") && o.lastPrice().doubleValue() == 67800.0)
                .verifyComplete();
    }
}
