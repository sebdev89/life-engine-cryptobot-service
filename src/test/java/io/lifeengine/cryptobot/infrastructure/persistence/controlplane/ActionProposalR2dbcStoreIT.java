package io.lifeengine.cryptobot.infrastructure.persistence.controlplane;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.lifeengine.cryptobot.application.controlplane.ControlPlaneExceptions;
import io.lifeengine.cryptobot.core.reliability.OutboxEvent;
import io.lifeengine.cryptobot.core.execution.ActionProposal;
import io.lifeengine.cryptobot.core.execution.AuditEvent;
import io.lifeengine.cryptobot.core.execution.ProposalStatus;
import io.lifeengine.cryptobot.core.execution.ProposalTransition;
import io.r2dbc.postgresql.PostgresqlConnectionConfiguration;
import io.r2dbc.postgresql.PostgresqlConnectionFactory;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
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

/**
 * (Gap G7, audit §15/§17, mandate §29): {@link ActionProposalR2dbcStore#commit} against a
 * real Postgres — Flyway {@code V1..V11} apply on a fresh container, no gating env var. Proves the
 * three things the in-memory replica ({@code InMemoryControlPlaneRepositories}) only asserts by
 * convention:
 *
 * <ul>
 *   <li>the optimistic-lock guard ({@code WHERE version = :expectedVersion AND status =
 *       :expectedStatus}) really is enforced by the database, not just by the caller re-reading
 *       first;
 *   <li>the partial unique index on {@code operation_id} really is unique across proposals;
 *   <li>the commit is one transaction: a failure while writing the outbox rows must not leave the
 *       proposal's status/version change applied.
 * </ul>
 *
 * <p>Opt-in ({@code *IT}, not picked up by {@code mvnw test}): run explicitly, e.g. {@code ./mvnw
 * test -Dtest=ActionProposalR2dbcStoreIT,OutboxEventR2dbcStoreIT,DeadLetterR2dbcStoreIT}. Needs
 * Docker (le-ci has it; an internal ticket — port contention between parallel Testcontainers jobs shows up as
 * "bind: address already in use", not a code regression).
 */
@Testcontainers
class ActionProposalR2dbcStoreIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
            .withDatabaseName("cryptobot_it").withUsername("cryptobot").withPassword("cryptobot");

    static final UUID OWNER = UUID.fromString("a0000000-0000-4000-8000-000000000001");

    static ActionProposalR2dbcStore store;
    static DatabaseClient db;

    @BeforeAll
    static void migrateAndWire() {
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration").load().migrate();

        PostgresqlConnectionFactory cf = new PostgresqlConnectionFactory(PostgresqlConnectionConfiguration.builder()
                .host(POSTGRES.getHost()).port(POSTGRES.getFirstMappedPort()).database(POSTGRES.getDatabaseName())
                .username(POSTGRES.getUsername()).password(POSTGRES.getPassword()).build());
        db = DatabaseClient.create(cf);
        TransactionalOperator tx = TransactionalOperator.create(new R2dbcTransactionManager(cf));
        // findAndRegisterModules(): a bare `new ObjectMapper()` does not auto-load jackson-datatype-jsr310,
        // and ActionProposal carries several java.time.Instant fields through the `doc` JSONB column.
        store = new ActionProposalR2dbcStore(db, new JsonDocs(new ObjectMapper().findAndRegisterModules()), tx);
    }

    @AfterAll
    static void cleanup() {
        // A fresh container per class run; nothing to drop by hand (unlike the shared-dev-Postgres ITs).
    }

    @BeforeEach
    void truncate() {
        db.sql("TRUNCATE audit_event, outbox_event, dead_letter, action_proposal, wallet CASCADE").fetch().rowsUpdated().block();
        db.sql("INSERT INTO wallet (id, owner_user_id, address, cluster) VALUES (:id, :owner, :addr, 'devnet')")
                .bind("id", WALLET).bind("owner", OWNER).bind("addr", "9xQeWvG816bUx9EPjHmaT23yvVM2ZWbrrpZb9PusVFin")
                .fetch().rowsUpdated().block();
    }

    static final UUID WALLET = UUID.fromString("b0000000-0000-4000-8000-000000000002");
    static final Instant T0 = Instant.parse("2026-09-21T12:00:00Z");

    /** A minimal, valid PROPOSED row — everything {@code commit} does not touch is null on purpose. */
    static ActionProposal proposed(UUID id) {
        return new ActionProposal(id, WALLET, OWNER, "9xQeWvG816bUx9EPjHmaT23yvVM2ZWbrrpZb9PusVFin", "devnet",
                ProposalStatus.PROPOSED, "REBALANCE", "t", null, "it", null, null, null, null, null, null, null, null, null,
                null, null, T0.plusSeconds(600), T0, T0, null, 0);
    }

    @Test
    @DisplayName("optimistic lock: two commits declaring the same version — one applies, the other gets StaleProposal from the real WHERE clause")
    void optimisticLockRealDatabaseGuard() {
        ActionProposal inserted = store.insert(proposed(UUID.randomUUID())).block();
        assertThat(inserted.version()).isZero();

        ActionProposal toSimulated = inserted.withStatus(ProposalStatus.SIMULATED, T0.plusSeconds(1));
        ProposalTransition first = new ProposalTransition(toSimulated, ProposalStatus.PROPOSED, 0, List.of(), List.of());
        ActionProposal afterFirst = store.commit(first).block();
        assertThat(afterFirst.status()).isEqualTo(ProposalStatus.SIMULATED);
        assertThat(afterFirst.version()).isEqualTo(1);

        // A second writer that read the row before the first commit lands: same declared version (0), now stale.
        ActionProposal alsoToSimulated = inserted.withStatus(ProposalStatus.SIMULATED, T0.plusSeconds(2));
        ProposalTransition second = new ProposalTransition(alsoToSimulated, ProposalStatus.PROPOSED, 0, List.of(), List.of());
        assertThatThrownBy(() -> store.commit(second).block()).isInstanceOf(ControlPlaneExceptions.StaleProposal.class);

        // The row reflects only the winner's commit — the loser's attempt left no trace.
        ActionProposal current = store.findByIdAndOwner(inserted.id(), OWNER).block();
        assertThat(current.version()).isEqualTo(1);
        assertThat(current.status()).isEqualTo(ProposalStatus.SIMULATED);
    }

    @Test
    @DisplayName("unique operation_id: two different proposals cannot commit under the same operationId")
    void uniqueOperationIdAcrossProposals() {
        ActionProposal p1 = store.insert(proposed(UUID.randomUUID())).block();
        ActionProposal p2 = store.insert(proposed(UUID.randomUUID())).block();
        UUID sharedOperation = UUID.randomUUID();

        ActionProposal p1Executing = p1.withOperation(sharedOperation, T0.plusSeconds(1)).withStatus(ProposalStatus.SIMULATED, T0.plusSeconds(1));
        ActionProposal committed1 = store.commit(new ProposalTransition(p1Executing, ProposalStatus.PROPOSED, 0, List.of(), List.of())).block();
        assertThat(committed1.operationId()).isEqualTo(sharedOperation);

        ActionProposal p2Executing = p2.withOperation(sharedOperation, T0.plusSeconds(1)).withStatus(ProposalStatus.SIMULATED, T0.plusSeconds(1));
        assertThatThrownBy(() -> store.commit(new ProposalTransition(p2Executing, ProposalStatus.PROPOSED, 0, List.of(), List.of())).block())
                .isInstanceOf(ControlPlaneExceptions.DuplicateOperation.class);

        // p2's row never moved: the unique violation rolled the whole transaction back.
        ActionProposal p2After = store.findByIdAndOwner(p2.id(), OWNER).block();
        assertThat(p2After.status()).isEqualTo(ProposalStatus.PROPOSED);
        assertThat(p2After.version()).isZero();
        assertThat(p2After.operationId()).isNull();
    }

    @Test
    @DisplayName("atomic commit: a failing outbox insert (unique id clash) leaves the proposal's status/version untouched")
    void atomicCommitRollsBackOnOutboxFailure() {
        ActionProposal inserted = store.insert(proposed(UUID.randomUUID())).block();
        ActionProposal next = inserted.withStatus(ProposalStatus.SIMULATED, T0.plusSeconds(1));

        // Two outbox rows sharing the same id: the first insert of the pair succeeds, the second
        // hits the primary key and blows up the transaction that also holds the status/version UPDATE.
        UUID clashingId = UUID.randomUUID();
        OutboxEvent event = OutboxEvent.pending(OutboxEvent.AGGREGATE_PROPOSAL, inserted.id(), OWNER, "TEST_EVENT", Map.of(), T0);
        OutboxEvent duplicateId = new OutboxEvent(clashingId, event.aggregateType(), event.aggregateId(), event.ownerUserId(), event.eventType(),
                event.payload(), event.status(), event.attempts(), event.nextAttemptAt(), event.lastError(), event.createdAt(), event.publishedAt());
        OutboxEvent alsoDuplicateId = new OutboxEvent(clashingId, event.aggregateType(), event.aggregateId(), event.ownerUserId(), "OTHER_EVENT",
                event.payload(), event.status(), event.attempts(), event.nextAttemptAt(), event.lastError(), event.createdAt(), event.publishedAt());

        AuditEvent audit = new AuditEvent(UUID.randomUUID(), OWNER, WALLET, inserted.id(), "TEST_AUDITED", "it", Map.of(), T0);
        ProposalTransition transition = new ProposalTransition(next, ProposalStatus.PROPOSED, 0, List.of(audit), List.of(duplicateId, alsoDuplicateId));

        assertThatThrownBy(() -> store.commit(transition).block()).isInstanceOf(ControlPlaneExceptions.Conflict.class);

        // Nothing from this transition landed: not the status/version change, not the audit row, not either outbox row.
        ActionProposal after = store.findByIdAndOwner(inserted.id(), OWNER).block();
        assertThat(after.status()).isEqualTo(ProposalStatus.PROPOSED);
        assertThat(after.version()).isZero();
        Long auditCount = db.sql("SELECT count(*) AS n FROM audit_event WHERE proposal_id = :id").bind("id", inserted.id())
                .map((row, meta) -> row.get("n", Long.class)).one().block();
        assertThat(auditCount).isZero();
        Long outboxCount = db.sql("SELECT count(*) AS n FROM outbox_event WHERE id = :id").bind("id", clashingId)
                .map((row, meta) -> row.get("n", Long.class)).one().block();
        assertThat(outboxCount).isZero();
    }
}
