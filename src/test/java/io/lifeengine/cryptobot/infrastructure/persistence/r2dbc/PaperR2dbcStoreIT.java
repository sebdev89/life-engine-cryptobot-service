package io.lifeengine.cryptobot.infrastructure.persistence.r2dbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.lifeengine.cryptobot.application.paper.PaperRepository.Portfolio;
import io.lifeengine.cryptobot.application.paper.PaperRepository.PriceCheck;
import io.lifeengine.cryptobot.application.paper.PaperRepository.Tick;
import io.r2dbc.postgresql.PostgresqlConnectionConfiguration;
import io.r2dbc.postgresql.PostgresqlConnectionFactory;
import java.math.BigDecimal;
import java.time.Instant;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.r2dbc.connection.R2dbcTransactionManager;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * KAN-1217 persistence against a real Postgres (Testcontainers, Flyway V1..V16): a tick and its portfolio state are
 * written together, the same candle cannot be applied twice (PK + strictly-later update), numerics round-trip
 * exactly, and price checks are idempotent per candle.
 *
 * <p>Opt-in ({@code *IT}): {@code ./mvnw test -Dtest=PaperR2dbcStoreIT}. Needs Docker.
 */
@Testcontainers
class PaperR2dbcStoreIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
            .withDatabaseName("cryptobot_it").withUsername("cryptobot").withPassword("cryptobot");

    static PaperR2dbcStore store;

    private static final Instant T0 = Instant.parse("2026-10-20T12:00:00Z");

    @BeforeAll
    static void migrateAndWire() {
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration").load().migrate();
        PostgresqlConnectionFactory cf = new PostgresqlConnectionFactory(PostgresqlConnectionConfiguration.builder()
                .host(POSTGRES.getHost()).port(POSTGRES.getFirstMappedPort()).database(POSTGRES.getDatabaseName())
                .username(POSTGRES.getUsername()).password(POSTGRES.getPassword()).build());
        store = new PaperR2dbcStore(DatabaseClient.create(cf), TransactionalOperator.create(new R2dbcTransactionManager(cf)));
    }

    private static Portfolio state(String id, Instant last, String sol, int ticks) {
        return new Portfolio(id, "band-rebalance", "targetSolWeight=0.5;bandBps=500", 30, 20, new BigDecimal("1000.000000"),
                new BigDecimal(sol), new BigDecimal("500.000000"), ticks, 0, new BigDecimal("0.000000"), T0, last);
    }

    private static Tick tick(String id, Instant bar, String sol) {
        return new Tick(id, bar, new BigDecimal("104.96000000"), BigDecimal.ZERO.setScale(6), false, BigDecimal.ZERO.setScale(6),
                new BigDecimal(sol), new BigDecimal("500.000000"), new BigDecimal("1000.000000"), new BigDecimal("0.5000000000"));
    }

    @Test
    @DisplayName("first tick inserts the portfolio; later ticks update it; numerics round-trip exactly")
    void recordsAndReadsBack() {
        store.record(state("p1", T0, "4.763719512", 1), tick("p1", T0, "4.763719512"), true).block();
        store.record(state("p1", T0.plusSeconds(3600), "4.763719513", 2), tick("p1", T0.plusSeconds(3600), "4.763719513"), false).block();

        Portfolio p = store.find("p1").block();
        assertThat(p.ticks()).isEqualTo(2);
        assertThat(p.sol()).isEqualByComparingTo("4.763719513");
        assertThat(p.lastBarTime()).isEqualTo(T0.plusSeconds(3600));
        assertThat(p.params()).isEqualTo("targetSolWeight=0.5;bandBps=500");
        assertThat(store.ticks("p1").collectList().block()).extracting(Tick::barTime).containsExactly(T0, T0.plusSeconds(3600));
    }

    @Test
    @DisplayName("the same candle cannot be applied twice, and a rejected tick leaves no row behind")
    void sameCandleTwiceFails() {
        store.record(state("p2", T0, "1", 1), tick("p2", T0, "1"), true).block();

        assertThatThrownBy(() -> store.record(state("p2", T0, "2", 2), tick("p2", T0, "2"), false).block())
                .isInstanceOf(IllegalStateException.class);
        assertThat(store.find("p2").block().sol()).isEqualByComparingTo("1");
        assertThat(store.ticks("p2").collectList().block()).hasSize(1);
    }

    @Test
    @DisplayName("price checks are idempotent per candle and keep a missing Jupiter price as null")
    void priceChecks() {
        Instant bar = T0.plusSeconds(7200);
        store.recordPriceCheck(new PriceCheck(bar, new BigDecimal("104.96"), new BigDecimal("105.01"), 5, T0)).block();
        store.recordPriceCheck(new PriceCheck(bar, new BigDecimal("1"), null, null, T0)).block();
        store.recordPriceCheck(new PriceCheck(bar.plusSeconds(3600), new BigDecimal("105"), null, null, T0)).block();

        assertThat(store.priceChecks().collectList().block())
                .satisfiesExactly(
                        c -> {
                            assertThat(c.coinbaseSpot()).isEqualByComparingTo("104.96");
                            assertThat(c.deviationBps()).isEqualTo(5);
                        },
                        c -> assertThat(c.jupiterPrice()).isNull());
    }
}
