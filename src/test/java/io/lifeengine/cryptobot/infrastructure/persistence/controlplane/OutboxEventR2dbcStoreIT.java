package io.lifeengine.cryptobot.infrastructure.persistence.controlplane;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.lifeengine.cryptobot.core.reliability.OutboxEvent;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.r2dbc.connection.R2dbcTransactionManager;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import reactor.core.publisher.Mono;

/**
 * KAN-604 (Gap G7, audit §15/§17, mandate §29): {@link OutboxEventR2dbcStore#processDue} against a
 * real Postgres, with two workers pulling concurrently — the scenario {@code SELECT … FOR UPDATE
 * SKIP LOCKED} exists for and the in-memory replica cannot reproduce (it has no row locks).
 *
 * <p>Opt-in ({@code *IT}), run explicitly: {@code ./mvnw test -Dtest=OutboxEventR2dbcStoreIT}.
 */
@Testcontainers
class OutboxEventR2dbcStoreIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
            .withDatabaseName("cryptobot_it").withUsername("cryptobot").withPassword("cryptobot");

    static final UUID OWNER = UUID.fromString("a0000000-0000-4000-8000-000000000001");
    static final UUID WALLET = UUID.fromString("b0000000-0000-4000-8000-000000000002");
    static final Instant T0 = Instant.parse("2026-09-21T12:00:00Z");

    static OutboxEventR2dbcStore store;
    static JsonDocs docs;
    static DatabaseClient db;

    @BeforeAll
    static void migrateAndWire() {
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration").load().migrate();

        PostgresqlConnectionFactoryHolder cf = new PostgresqlConnectionFactoryHolder(POSTGRES);
        db = DatabaseClient.create(cf.factory());
        TransactionalOperator tx = TransactionalOperator.create(new R2dbcTransactionManager(cf.factory()));
        docs = new JsonDocs(new ObjectMapper().findAndRegisterModules());
        store = new OutboxEventR2dbcStore(db, docs, tx);
    }

    @BeforeEach
    void truncate() {
        db.sql("TRUNCATE outbox_event, dead_letter, action_proposal, wallet CASCADE").fetch().rowsUpdated().block();
        db.sql("INSERT INTO wallet (id, owner_user_id, address, cluster) VALUES (:id, :owner, :addr, 'devnet')")
                .bind("id", WALLET).bind("owner", OWNER).bind("addr", "9xQeWvG816bUx9EPjHmaT23yvVM2ZWbrrpZb9PusVFin")
                .fetch().rowsUpdated().block();
    }

    /** {@code n} PENDING rows, due now, inserted directly (no proposal commit needed for this test). */
    private static List<OutboxEvent> insertPending(int n) {
        return java.util.stream.IntStream.range(0, n).mapToObj(i -> {
            OutboxEvent e = OutboxEvent.pending(OutboxEvent.AGGREGATE_PROPOSAL, UUID.randomUUID(), OWNER, "TEST_EVENT", Map.of("i", i), T0);
            OutboxEventR2dbcStore.insert(db, docs, e).block();
            return e;
        }).toList();
    }

    /**
     * KAN-604 — two real, concurrently open workers. Worker1 is a plain JDBC connection that runs
     * the exact same {@code SELECT … FOR UPDATE SKIP LOCKED} and deliberately never commits (it is
     * "still processing", the state a crashed worker would leave behind); worker2 is the real
     * {@link OutboxEventR2dbcStore#processDue}, running concurrently while worker1's transaction is
     * still open. The lock-hold is a real, uncommitted Postgres transaction — not a sleep timed
     * against a reactive scheduler — so the overlap is deterministic instead of racy in CI (KAN-296).
     */
    @Test
    @DisplayName("SKIP LOCKED, 2 concurrent workers: while worker1 still holds half the rows (uncommitted), worker2 only ever gets the other half — no id delivered twice")
    void secondWorkerNeverTouchesRowsTheFirstStillHolds() throws Exception {
        int total = 20;
        List<OutboxEvent> pending = insertPending(total);
        Set<UUID> allIds = pending.stream().map(OutboxEvent::id).collect(Collectors.toSet());

        List<UUID> worker1Locked = new ArrayList<>();
        try (Connection worker1 = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            worker1.setAutoCommit(false);
            try (PreparedStatement ps = worker1.prepareStatement(
                    "SELECT id FROM outbox_event WHERE status = 'PENDING' ORDER BY created_at ASC, id ASC LIMIT " + (total / 2) + " FOR UPDATE SKIP LOCKED")) {
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        worker1Locked.add((UUID) rs.getObject("id"));
                    }
                }
            }
            assertThat(worker1Locked).as("worker1 holds exactly half the rows, uncommitted").hasSize(total / 2);

            // worker2: the real reactive store, in a real concurrent transaction — must SKIP everything worker1 still holds.
            List<UUID> delivered2 = new CopyOnWriteArrayList<>();
            long n2 = store.processDue(T0.plusSeconds(1), total, e -> {
                delivered2.add(e.id());
                return Mono.just(new OutboxRepository.Outcome.Published(T0.plusSeconds(5)));
            }).block();

            assertThat(n2).as("only the unlocked half is available to worker2 while worker1 is still open").isEqualTo(total / 2);
            assertThat(delivered2).as("no id counted twice within worker2's own batch").hasSize(new HashSet<>(delivered2).size());
            assertThat(delivered2).as("worker2 never touches a row worker1 still holds").doesNotContainAnyElementsOf(worker1Locked);
            Set<UUID> remaining = new HashSet<>(allIds);
            remaining.removeAll(delivered2);
            assertThat(remaining).as("everything worker2 did not get is exactly what worker1 is holding").containsExactlyInAnyOrderElementsOf(worker1Locked);

            Long publishedWhileWorker1Open = db.sql("SELECT count(*) AS n FROM outbox_event WHERE status = 'PUBLISHED'")
                    .map((row, meta) -> row.get("n", Long.class)).one().block();
            assertThat(publishedWhileWorker1Open).as("worker2's half is published; worker1's half is still PENDING (never touched, only locked)").isEqualTo(total / 2);

            worker1.rollback(); // worker1 "crashes" without ever acting — its half stays PENDING, free for the next run.
        }

        // A later worker (crash recovery / the next tick) picks up exactly what worker1 left behind.
        List<UUID> delivered3 = new CopyOnWriteArrayList<>();
        long n3 = store.processDue(T0.plusSeconds(1), total, e -> {
            delivered3.add(e.id());
            return Mono.just(new OutboxRepository.Outcome.Published(T0.plusSeconds(5)));
        }).block();
        assertThat(n3).isEqualTo(total / 2);
        assertThat(delivered3).containsExactlyInAnyOrderElementsOf(worker1Locked);

        Long publishedCount = db.sql("SELECT count(*) AS n FROM outbox_event WHERE status = 'PUBLISHED'")
                .map((row, meta) -> row.get("n", Long.class)).one().block();
        assertThat(publishedCount).as("every one of the original 20 events ends up published exactly once").isEqualTo(total);
    }

    @Test
    @DisplayName("nothing due, nothing delivered: an empty PENDING set is a legitimate SKIP LOCKED result, not an error")
    void noDueRowsIsANoop() {
        Long delivered = store.processDue(T0, 10, e -> Mono.just(new OutboxRepository.Outcome.Published(T0))).block();
        assertThat(delivered).isZero();
    }

    /** Keeps the connection-factory wiring in one place for the two workers to share the same container. */
    private record PostgresqlConnectionFactoryHolder(PostgreSQLContainer<?> container) {
        io.r2dbc.postgresql.PostgresqlConnectionFactory factory() {
            return new io.r2dbc.postgresql.PostgresqlConnectionFactory(io.r2dbc.postgresql.PostgresqlConnectionConfiguration.builder()
                    .host(container.getHost()).port(container.getFirstMappedPort()).database(container.getDatabaseName())
                    .username(container.getUsername()).password(container.getPassword()).build());
        }
    }
}
