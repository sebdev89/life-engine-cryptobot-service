package io.lifeengine.cryptobot.infrastructure.persistence.controlplane;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.lifeengine.cryptobot.domain.reliability.DeadLetter;
import io.r2dbc.postgresql.PostgresqlConnectionConfiguration;
import io.r2dbc.postgresql.PostgresqlConnectionFactory;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.r2dbc.core.DatabaseClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * KAN-604 (Gap G7, audit §15/§17, mandate §29): {@link DeadLetterR2dbcStore#resolve} against a
 * real Postgres — the {@code WHERE resolved_at IS NULL} guard (V9, KAN-571/KAN-501) is what makes
 * a letter resolvable at most once; the in-memory replica enforces the same rule in application
 * code, this proves the database does too.
 *
 * <p>Opt-in ({@code *IT}), run explicitly: {@code ./mvnw test -Dtest=DeadLetterR2dbcStoreIT}.
 */
@Testcontainers
class DeadLetterR2dbcStoreIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
            .withDatabaseName("cryptobot_it").withUsername("cryptobot").withPassword("cryptobot");

    static final UUID OWNER = UUID.fromString("a0000000-0000-4000-8000-000000000001");
    static final Instant T0 = Instant.parse("2026-09-21T12:00:00Z");

    static DeadLetterR2dbcStore store;
    static DatabaseClient db;

    @BeforeAll
    static void migrateAndWire() {
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration").load().migrate();

        PostgresqlConnectionFactory cf = new PostgresqlConnectionFactory(PostgresqlConnectionConfiguration.builder()
                .host(POSTGRES.getHost()).port(POSTGRES.getFirstMappedPort()).database(POSTGRES.getDatabaseName())
                .username(POSTGRES.getUsername()).password(POSTGRES.getPassword()).build());
        db = DatabaseClient.create(cf);
        store = new DeadLetterR2dbcStore(db, new JsonDocs(new ObjectMapper().findAndRegisterModules()));
    }

    @BeforeEach
    void truncate() {
        db.sql("TRUNCATE dead_letter CASCADE").fetch().rowsUpdated().block();
    }

    @Test
    @DisplayName("a letter resolves at most once: the second resolve() on an already-resolved row is a no-op, guarded by WHERE resolved_at IS NULL")
    void onlyOneResolutionEver() {
        DeadLetter letter = DeadLetter.of(DeadLetter.Source.OUTBOX, UUID.randomUUID(), null, OWNER, "retries exhausted", Map.of("attempts", 5), T0);
        DeadLetter appended = store.append(letter).block();
        assertThat(appended.resolvedAt()).isNull();

        DeadLetter firstResolution = store.resolve(letter.id(), T0.plusSeconds(60), "ops@life-engine.app", "verified on the explorer: never landed", DeadLetter.Outcome.RESOLVED).block();
        assertThat(firstResolution).isNotNull();
        assertThat(firstResolution.resolvedAt()).isEqualTo(T0.plusSeconds(60));
        assertThat(firstResolution.resolvedBy()).isEqualTo("ops@life-engine.app");
        assertThat(firstResolution.outcome()).isEqualTo(DeadLetter.Outcome.RESOLVED);

        // A second, different attempt to resolve the same letter: the UPDATE matches zero rows (resolved_at is
        // no longer NULL), so the store reports "nothing happened" instead of silently overwriting the first call.
        DeadLetter secondAttempt = store.resolve(letter.id(), T0.plusSeconds(120), "someone-else@life-engine.app", "trying to requeue it now", DeadLetter.Outcome.REQUEUED).block();
        assertThat(secondAttempt).as("the second resolve() must not report success").isNull();

        DeadLetter stored = store.findById(letter.id()).block();
        assertThat(stored.resolvedAt()).as("unchanged by the second attempt").isEqualTo(T0.plusSeconds(60));
        assertThat(stored.resolvedBy()).isEqualTo("ops@life-engine.app");
        assertThat(stored.resolution()).isEqualTo("verified on the explorer: never landed");
        assertThat(stored.outcome()).isEqualTo(DeadLetter.Outcome.RESOLVED);
    }

    @Test
    @DisplayName("resolving an unknown id is empty, not an error")
    void resolvingAnUnknownLetterIsEmpty() {
        DeadLetter result = store.resolve(UUID.randomUUID(), T0, "ops@life-engine.app", "note", DeadLetter.Outcome.RESOLVED).block();
        assertThat(result).isNull();
    }
}
