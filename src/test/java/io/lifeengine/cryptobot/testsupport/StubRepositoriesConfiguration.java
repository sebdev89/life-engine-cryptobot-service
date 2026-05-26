package io.lifeengine.cryptobot.testsupport;

import io.lifeengine.cryptobot.infrastructure.persistence.r2dbc.IndicatorSnapshotRepository;
import io.lifeengine.cryptobot.infrastructure.persistence.r2dbc.MarketObservationRepository;
import io.lifeengine.cryptobot.infrastructure.persistence.r2dbc.PriceZoneRepository;
import io.lifeengine.cryptobot.infrastructure.persistence.r2dbc.TradeJournalEntryRepository;
import io.lifeengine.cryptobot.infrastructure.persistence.r2dbc.WatchlistEntryRepository;
import org.mockito.Mockito;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Provides Mockito stubs for the five R2DBC repositories so {@code @SpringBootTest} can boot the
 * context without a live Postgres connection. Individual tests can override each bean via
 * {@code @MockBean}/{@code @SpyBean} (or simply re-stub the existing mock) if they need richer
 * behaviour.
 */
@TestConfiguration
public class StubRepositoriesConfiguration {

    @Bean
    WatchlistEntryRepository watchlistEntryRepository() {
        return Mockito.mock(WatchlistEntryRepository.class);
    }

    @Bean
    PriceZoneRepository priceZoneRepository() {
        return Mockito.mock(PriceZoneRepository.class);
    }

    @Bean
    MarketObservationRepository marketObservationRepository() {
        return Mockito.mock(MarketObservationRepository.class);
    }

    @Bean
    TradeJournalEntryRepository tradeJournalEntryRepository() {
        return Mockito.mock(TradeJournalEntryRepository.class);
    }

    @Bean
    IndicatorSnapshotRepository indicatorSnapshotRepository() {
        return Mockito.mock(IndicatorSnapshotRepository.class);
    }
}
