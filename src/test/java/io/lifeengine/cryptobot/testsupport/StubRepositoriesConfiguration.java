package io.lifeengine.cryptobot.testsupport;

import io.lifeengine.cryptobot.domain.MarketReviewRun;
import io.lifeengine.cryptobot.infrastructure.persistence.r2dbc.IndicatorSnapshotRepository;
import io.lifeengine.cryptobot.infrastructure.persistence.r2dbc.MarketObservationRepository;
import io.lifeengine.cryptobot.infrastructure.persistence.r2dbc.MarketReviewRunRepository;
import io.lifeengine.cryptobot.infrastructure.persistence.r2dbc.PriceZoneRepository;
import io.lifeengine.cryptobot.infrastructure.persistence.r2dbc.TradeJournalEntryRepository;
import io.lifeengine.cryptobot.infrastructure.persistence.r2dbc.WatchlistEntryRepository;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Provides Mockito stubs for the R2DBC repositories so {@code @SpringBootTest} can boot the
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

    /**
     * In tests, {@link MarketReviewRunRepository} echoes back the value passed to {@code insert} /
     * {@code update} so the MarketReviewService can stay on the happy path. Tests that need richer
     * lookups can re-stub specific methods.
     */
    @Bean
    MarketReviewRunRepository marketReviewRunRepository() {
        MarketReviewRunRepository mock = Mockito.mock(MarketReviewRunRepository.class);
        Mockito.when(mock.insert(ArgumentMatchers.any()))
                .thenAnswer(inv -> Mono.just(inv.<MarketReviewRun>getArgument(0)));
        Mockito.when(mock.update(ArgumentMatchers.any()))
                .thenAnswer(inv -> Mono.just(inv.<MarketReviewRun>getArgument(0)));
        Mockito.when(mock.findById(ArgumentMatchers.any())).thenReturn(Mono.empty());
        Mockito.when(mock.findByRuntimeRunId(ArgumentMatchers.any())).thenReturn(Mono.empty());
        Mockito.when(mock.findLatestBySymbol(ArgumentMatchers.anyString())).thenReturn(Mono.empty());
        Mockito.when(mock.findRecentBySymbol(ArgumentMatchers.anyString(), ArgumentMatchers.anyInt()))
                .thenReturn(Flux.empty());
        return mock;
    }

    // ---- Colosseum control plane: real in-memory stores, not mocks ------------------------
    @Bean
    io.lifeengine.cryptobot.infrastructure.persistence.controlplane.WalletRepository walletRepository() {
        return InMemoryControlPlaneRepositories.wallets();
    }

    @Bean
    io.lifeengine.cryptobot.infrastructure.persistence.controlplane.PortfolioSnapshotRepository portfolioSnapshotRepository() {
        return InMemoryControlPlaneRepositories.snapshots();
    }

    @Bean
    io.lifeengine.cryptobot.infrastructure.persistence.controlplane.AdvisorMessageRepository advisorMessageRepository() {
        return InMemoryControlPlaneRepositories.messages();
    }

    @Bean
    io.lifeengine.cryptobot.infrastructure.persistence.controlplane.ActionProposalRepository actionProposalRepository() {
        return InMemoryControlPlaneRepositories.proposals();
    }

    @Bean
    io.lifeengine.cryptobot.infrastructure.persistence.controlplane.AuditEventRepository auditEventRepository() {
        return InMemoryControlPlaneRepositories.audit();
    }

    @Bean
    io.lifeengine.cryptobot.infrastructure.persistence.controlplane.OutboxRepository outboxRepository() {
        return InMemoryControlPlaneRepositories.outbox();
    }

    @Bean
    io.lifeengine.cryptobot.infrastructure.persistence.controlplane.DeadLetterRepository deadLetterRepository() {
        return InMemoryControlPlaneRepositories.deadLetters();
    }

    @Bean
    io.lifeengine.cryptobot.infrastructure.persistence.controlplane.ReceiptRepository receiptRepository() {
        return InMemoryControlPlaneRepositories.receipts();
    }

    @Bean
    io.lifeengine.cryptobot.infrastructure.persistence.controlplane.AnchorRepository anchorRepository() {
        return new InMemoryAnchorRepository();
    }

    /** KAN-818: Proof of Value — identities and value events. */
    @Bean
    io.lifeengine.cryptobot.proofofvalue.PovIdentityRepository povIdentityRepository() {
        return InMemoryPovRepositories.identities();
    }

    @Bean
    io.lifeengine.cryptobot.proofofvalue.ValueEventRepository povValueEventRepository() {
        return InMemoryPovRepositories.events();
    }

    /** KAN-819: knowledge assets. */
    @Bean
    io.lifeengine.cryptobot.proofofvalue.KnowledgeAssetRepository povKnowledgeAssetRepository() {
        return InMemoryPovRepositories.assets();
    }

    /** KAN-822: immediate-reward distributions and payouts. */
    @Bean
    io.lifeengine.cryptobot.proofofvalue.PayoutRepository povPayoutRepository() {
        return InMemoryPovRepositories.payouts();
    }

    /** KAN-824: revenue events (their payouts share the payouts store). */
    @Bean
    io.lifeengine.cryptobot.proofofvalue.RevenueRepository povRevenueRepository() {
        return InMemoryPovRepositories.revenues();
    }

    /** KAN-393: the DAG walks over the same in-memory receipts and edges. */
    @Bean
    io.lifeengine.cryptobot.infrastructure.persistence.controlplane.LineageRepository lineageRepository() {
        return new InMemoryLineageRepository();
    }
}
