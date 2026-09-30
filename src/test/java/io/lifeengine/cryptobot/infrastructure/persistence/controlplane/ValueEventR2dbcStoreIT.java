package io.lifeengine.cryptobot.infrastructure.persistence.controlplane;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.lifeengine.cryptobot.application.receipt.ReceiptDraft;
import io.lifeengine.cryptobot.application.receipt.ReceiptService;
import io.lifeengine.cryptobot.core.receipts.Digests;
import io.lifeengine.cryptobot.core.receipts.IntelligenceReceipt;
import io.lifeengine.cryptobot.core.receipts.ReceiptBody;
import io.lifeengine.cryptobot.core.receipts.ReceiptInput;
import io.lifeengine.cryptobot.core.receipts.ReceiptKind;
import io.lifeengine.cryptobot.core.receipts.ReceiptSigningKey;
import io.lifeengine.cryptobot.core.receipts.ReproducibilityLevel;
import io.r2dbc.postgresql.PostgresqlConnectionConfiguration;
import io.r2dbc.postgresql.PostgresqlConnectionFactory;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
 * KAN-818 against a real Postgres: {@code V12} applies on top of {@code V1..V11}, a receipt of kind
 * VALUE_EVENT is accepted by the widened kind constraint, {@link ValueEventR2dbcStore} round-trips
 * and is idempotent by hash, and the database itself refuses a self-accepted event.
 *
 * <p>Opt-in ({@code *IT}): {@code ./mvnw test -Dtest=ValueEventR2dbcStoreIT} (needs Docker).
 */
@Testcontainers
class ValueEventR2dbcStoreIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
            .withDatabaseName("cryptobot_it").withUsername("cryptobot").withPassword("cryptobot");

    static final UUID OWNER = UUID.fromString("a0000000-0000-4000-8000-000000000001");
    static final Instant T0 = Instant.parse("2026-09-30T10:00:00Z");

    static ValueEventR2dbcStore store;
    static ReceiptService receipts;

    @BeforeAll
    static void migrateAndWire() {
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration").load().migrate();
        PostgresqlConnectionFactory cf = new PostgresqlConnectionFactory(PostgresqlConnectionConfiguration.builder()
                .host(POSTGRES.getHost()).port(POSTGRES.getFirstMappedPort()).database(POSTGRES.getDatabaseName())
                .username(POSTGRES.getUsername()).password(POSTGRES.getPassword()).build());
        DatabaseClient db = DatabaseClient.create(cf);
        store = new ValueEventR2dbcStore(db);
        receipts = new ReceiptService(new ReceiptR2dbcStore(db, new JsonDocs(new ObjectMapper().findAndRegisterModules()),
                TransactionalOperator.create(new R2dbcTransactionManager(cf))), ReceiptSigningKey.generate("it-key"));
    }

    static String issueValueEventReceipt(String nonce, String eventHash) {
        ReceiptBody body = new ReceiptBody(null, ReceiptKind.VALUE_EVENT, OWNER.toString(), OWNER.toString(), "verticals@1", List.of(),
                List.of(new ReceiptInput(ReceiptInput.CONTRIBUTION_EVIDENCE, Digests.sha256("diff-" + nonce))), null, null, null, null, Map.of(),
                new ReceiptBody.Output(eventHash, "value-event/1", null), null, null, ReproducibilityLevel.L0_SIGNED, T0, T0.plusSeconds(60), nonce, null);
        IntelligenceReceipt r = receipts.issue(ReceiptDraft.of(body)).block();
        return r.receiptHash();
    }

    static ValueEventRepository.Row row(String nonce, String contributor, String acceptor) {
        String eventHash = Digests.sha256("event-" + nonce);
        return new ValueEventRepository.Row(eventHash, issueValueEventReceipt(nonce, eventHash), OWNER.toString(), OWNER, contributor, "AGENT", contributor,
                "CODE", Digests.sha256("diff-" + nonce), "github:repo#pr/1", acceptor, "human-approval", Digests.sha256("approval"),
                "{\"schema\":\"value-event/1\"}", T0, T0.plusSeconds(120));
    }

    @Test
    @DisplayName("V12 applies; insert round-trips every column; the same hash again is a no-op; reads are owner-scoped and evidence lookup works")
    void roundTripAndIdempotent() {
        ValueEventRepository.Row r = row("rt-" + UUID.randomUUID(), "verticals@1", "sebas");
        ValueEventRepository.Row stored = store.insert(r).block();
        assertThat(stored).isEqualTo(r);
        assertThat(store.insert(r).block()).isEqualTo(r);
        assertThat(store.findByHashAndOwner(r.valueEventHash(), OWNER).block()).isEqualTo(r);
        assertThat(store.findByHashAndOwner(r.valueEventHash(), UUID.randomUUID()).block()).isNull();
        assertThat(store.findByOwner(OWNER, 50).collectList().block()).contains(r);
        assertThat(store.findByEvidence(OWNER, r.evidenceHash()).collectList().block()).containsExactly(r);
    }

    @Test
    @DisplayName("the database refuses an event accepted by its own contributor (chk_value_event_not_self_accepted)")
    void selfAcceptanceRefusedByTheDatabase() {
        ValueEventRepository.Row bad = row("self-" + UUID.randomUUID(), "verticals@1", "verticals@1");
        assertThatThrownBy(() -> store.insert(bad).block()).hasMessageContaining("chk_value_event_not_self_accepted");
    }
}
