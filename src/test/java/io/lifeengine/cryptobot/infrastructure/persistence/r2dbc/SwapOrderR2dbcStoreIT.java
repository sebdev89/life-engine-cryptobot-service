package io.lifeengine.cryptobot.infrastructure.persistence.r2dbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.lifeengine.cryptobot.application.pilot.SwapOrder;
import io.r2dbc.postgresql.PostgresqlConnectionConfiguration;
import io.r2dbc.postgresql.PostgresqlConnectionFactory;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.r2dbc.core.DatabaseClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * KAN-1249 pilot persistence against a real Postgres (Testcontainers, Flyway V1..V17): a state change happens once or
 * not at all, an approval's jti is UNIQUE across orders (so a restart cannot let a token approve twice), the
 * idempotency key is UNIQUE, the CHECK constraints hold, and the audit trail is append-only and ordered.
 *
 * <p>Opt-in ({@code *IT}): {@code ./mvnw test -Dtest=SwapOrderR2dbcStoreIT}. Needs Docker.
 */
@Testcontainers
class SwapOrderR2dbcStoreIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
            .withDatabaseName("cryptobot_it").withUsername("cryptobot").withPassword("cryptobot");

    static SwapOrderR2dbcStore store;
    static DatabaseClient db;
    static final Instant T0 = Instant.parse("2026-10-23T12:00:00Z");
    static final UUID APPROVER = UUID.randomUUID();

    @BeforeAll
    static void migrateAndWire() {
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration").load().migrate();
        PostgresqlConnectionFactory cf = new PostgresqlConnectionFactory(PostgresqlConnectionConfiguration.builder()
                .host(POSTGRES.getHost()).port(POSTGRES.getFirstMappedPort()).database(POSTGRES.getDatabaseName())
                .username(POSTGRES.getUsername()).password(POSTGRES.getPassword()).build());
        db = DatabaseClient.create(cf);
        store = new SwapOrderR2dbcStore(db);
    }

    static SwapOrder pending(String key, long inAmount) {
        return pending(key, inAmount, UUID.randomUUID());
    }

    static SwapOrder pending(String key, long inAmount, UUID creator) {
        return new SwapOrder(UUID.randomUUID(), key, creator, SwapOrder.Status.PENDING_APPROVAL, SwapOrder.Direction.SOL_USDC, "devnet",
                "5tzFkiKscXHK5ZXCGbXZxdw7gTjjD1mBwuoFbhUvuAi9", inAmount, 1_026_997L, 50, "{\"p\":1}", "a".repeat(64), "b".repeat(64), null, null,
                null, T0.plusSeconds(120), null, null, T0, T0);
    }

    @Test
    @DisplayName("insert, find by id and by (creator, idempotency key); the key is UNIQUE per creator only")
    void insertsAndFinds() {
        UUID creator = UUID.randomUUID();
        SwapOrder o = store.insert(pending("it-k1", 10_000_000L, creator)).block();
        assertThat(store.find(o.id()).block().inAmount()).isEqualTo(10_000_000L);
        assertThat(store.findByIdempotencyKey(creator, "it-k1").block().id()).isEqualTo(o.id());
        assertThat(store.find(o.id()).block().expiresAt()).isEqualTo(T0.plusSeconds(120));
        // the same creator cannot reuse it; another creator can have the same key
        assertThatThrownBy(() -> store.insert(pending("it-k1", 1L, creator)).block())
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        SwapOrder other = store.insert(pending("it-k1", 1L, UUID.randomUUID())).block();
        assertThat(other.id()).isNotEqualTo(o.id());
        assertThat(store.findByIdempotencyKey(other.createdBy(), "it-k1").block().id()).isEqualTo(other.id());
    }

    @Test
    @DisplayName("approve is single use, and one jti approves one order across the whole table")
    void approveOnceAndJtiUnique() {
        SwapOrder a = store.insert(pending("it-a", 1L)).block();
        SwapOrder b = store.insert(pending("it-b", 1L)).block();
        Instant at = T0.plusSeconds(10).truncatedTo(ChronoUnit.MILLIS);

        SwapOrder approved = store.approve(a.id(), APPROVER, at, "jti-it-1", at.plusSeconds(120)).block();
        assertThat(approved.status()).isEqualTo(SwapOrder.Status.APPROVED);
        assertThat(approved.approvedBy()).isEqualTo(APPROVER);
        assertThat(approved.approvalJti()).isEqualTo("jti-it-1");
        assertThat(approved.expiresAt()).isEqualTo(at.plusSeconds(120));

        // the same order again: no longer pending
        assertThat(store.approve(a.id(), APPROVER, at, "jti-it-2", at).block()).isNull();
        // the same jti on another order: refused by the UNIQUE constraint, and b stays pending
        assertThat(store.approve(b.id(), APPROVER, at, "jti-it-1", at).block()).isNull();
        assertThat(store.find(b.id()).block().status()).isEqualTo(SwapOrder.Status.PENDING_APPROVAL);
    }

    @Test
    @DisplayName("transitions are conditional on the current status")
    void conditionalTransitions() {
        SwapOrder o = store.insert(pending("it-t", 1L)).block();
        assertThat(store.transition(o.id(), SwapOrder.Status.PENDING_APPROVAL, SwapOrder.Status.REJECTED, "rejected").block().status())
                .isEqualTo(SwapOrder.Status.REJECTED);
        assertThat(store.transition(o.id(), SwapOrder.Status.PENDING_APPROVAL, SwapOrder.Status.EXPIRED, null).block()).isNull();
        assertThat(store.find(o.id()).block().error()).isEqualTo("rejected");
    }

    @Test
    @DisplayName("the signature is written once, only on an EXECUTING order")
    void signatureOnceOnlyWhileExecuting() {
        SwapOrder o = store.insert(pending("it-sig", 1L)).block();
        // still pending: no signature
        assertThat(store.markSigned(o.id(), "sig-1").block()).isNull();
        store.approve(o.id(), APPROVER, T0, "jti-it-sig", T0.plusSeconds(120)).block();
        store.transition(o.id(), SwapOrder.Status.APPROVED, SwapOrder.Status.EXECUTING, null).block();
        assertThat(store.markSigned(o.id(), "sig-1").block().txSignature()).isEqualTo("sig-1");
        // a second one never replaces it
        assertThat(store.markSigned(o.id(), "sig-2").block()).isNull();
        assertThat(store.find(o.id()).block().txSignature()).isEqualTo("sig-1");
    }

    @Test
    @DisplayName("spentSince: orders that may have moved funds, approved inside the window")
    void spentSince() {
        Instant since = T0.plusSeconds(3_600);
        SwapOrder executing = store.insert(pending("it-spent-1", 1L)).block();
        store.approve(executing.id(), APPROVER, since.plusSeconds(1), "jti-it-spent-1", since.plusSeconds(120)).block();
        store.transition(executing.id(), SwapOrder.Status.APPROVED, SwapOrder.Status.EXECUTING, null).block();
        SwapOrder old = store.insert(pending("it-spent-2", 1L)).block();
        store.approve(old.id(), APPROVER, since.minusSeconds(1), "jti-it-spent-2", since.plusSeconds(120)).block();
        store.transition(old.id(), SwapOrder.Status.APPROVED, SwapOrder.Status.EXECUTING, null).block();
        SwapOrder onlyApproved = store.insert(pending("it-spent-3", 1L)).block();
        store.approve(onlyApproved.id(), APPROVER, since.plusSeconds(2), "jti-it-spent-3", since.plusSeconds(120)).block();
        assertThat(store.spentSince(since).map(SwapOrder::id).collectList().block()).containsExactly(executing.id());
    }

    @Test
    @DisplayName("the audit trail is ordered and accepts null actor and detail")
    void auditTrail() {
        SwapOrder o = store.insert(pending("it-audit", 1L)).block();
        store.audit(o.id(), "CREATED", UUID.randomUUID(), o.previewHash(), null).block();
        store.audit(o.id(), "EXPIRED", null, o.previewHash(), null).block();
        store.audit(o.id(), "NOTE", null, null, "detail").block();
        assertThat(store.auditEvents(o.id()).collectList().block()).containsExactly("CREATED", "EXPIRED", "NOTE");
    }

    @Test
    @DisplayName("the audit table is append-only for real: UPDATE and DELETE are refused by a trigger")
    void auditIsAppendOnly() {
        SwapOrder o = store.insert(pending("it-immutable", 1L)).block();
        store.audit(o.id(), "CREATED", null, o.previewHash(), "original").block();
        assertThatThrownBy(() -> db.sql("UPDATE swap_order_audit SET detail = 'edited' WHERE order_id = :id").bind("id", o.id()).fetch().rowsUpdated()
                .block()).hasMessageContaining("append-only");
        assertThatThrownBy(() -> db.sql("DELETE FROM swap_order_audit WHERE order_id = :id").bind("id", o.id()).fetch().rowsUpdated().block())
                .hasMessageContaining("append-only");
        assertThat(store.auditEvents(o.id()).collectList().block()).containsExactly("CREATED");
    }

    @Test
    @DisplayName("CHECK constraints: no zero amount, no unknown status or cluster")
    void constraints() {
        assertThatThrownBy(() -> store.insert(pending("it-zero", 0L)).block()).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> store.insert(pending("it-huge", 1_000_000_000_000_001L)).block()).isInstanceOf(RuntimeException.class);
        SwapOrder badCluster = new SwapOrder(UUID.randomUUID(), "it-cluster", UUID.randomUUID(), SwapOrder.Status.PENDING_APPROVAL,
                SwapOrder.Direction.SOL_USDC, "testnet", "o", 1L, 1L, 50, "{}", "a".repeat(64), "b".repeat(64), null, null, null, T0, null, null, T0, T0);
        assertThatThrownBy(() -> store.insert(badCluster).block()).isInstanceOf(RuntimeException.class);
    }
}
