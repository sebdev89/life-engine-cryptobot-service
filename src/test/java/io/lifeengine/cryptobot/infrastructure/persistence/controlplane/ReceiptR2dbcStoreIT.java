package io.lifeengine.cryptobot.infrastructure.persistence.controlplane;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.lifeengine.cryptobot.application.controlplane.ControlPlaneExceptions;
import io.lifeengine.cryptobot.application.receipt.DeterministicReproducer;
import io.lifeengine.cryptobot.application.receipt.ReceiptDraft;
import io.lifeengine.cryptobot.application.receipt.ReceiptService;
import io.lifeengine.cryptobot.core.receipts.DeterministicInference;
import io.lifeengine.cryptobot.core.receipts.Digests;
import io.lifeengine.cryptobot.core.receipts.IntelligenceReceipt;
import io.lifeengine.cryptobot.core.receipts.ReceiptBody;
import io.lifeengine.cryptobot.core.receipts.ReceiptEdge;
import io.lifeengine.cryptobot.core.receipts.ReceiptInput;
import io.lifeengine.cryptobot.core.receipts.ReceiptKind;
import io.lifeengine.cryptobot.core.receipts.ReceiptSigningKey;
import io.lifeengine.cryptobot.core.receipts.ReproducibilityLevel;
import io.lifeengine.cryptobot.trading.risk.DeterministicDecision;
import io.lifeengine.cryptobot.trading.risk.DeterministicRiskEngine;
import io.lifeengine.cryptobot.trading.risk.RiskInput;
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
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.r2dbc.connection.R2dbcTransactionManager;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.transaction.reactive.TransactionalOperator;

/**
 * The real store against a real Postgres (KAN-391): migrations {@code V6} and {@code V7} (KAN-392)
 * apply on top of {@code V1..V5} in a scratch schema, and {@link ReceiptR2dbcStore} keeps the same contract the
 * in-memory store promises — content addressing, the nonce guard, the FK on parents, the
 * round-trip of body / canonical bytes / signature / anchor columns.
 *
 * <p>Opt-in ({@code *IT}, not picked up by {@code mvn test}): set {@code CRYPTOBOT_IT_PG_HOST},
 * {@code _PORT}, {@code _DB}, {@code _USER}, {@code _PASSWORD} (the dev box: {@code life-engine-postgres:5433},
 * {@code life_engine_cryptobot}) and run {@code ./mvnw test -Dtest=ReceiptR2dbcStoreIT}. It
 * works in its own schema {@code kan391_it} (created and migrated by Flyway, never dropped by the
 * test — drop it by hand when it is no longer wanted), so the dev database's {@code public}
 * schema is never touched, and every run uses fresh tenants and nonces so re-runs do not collide.
 */
@EnabledIfEnvironmentVariable(named = "CRYPTOBOT_IT_PG_HOST", matches = ".+")
class ReceiptR2dbcStoreIT {

    static final String SCHEMA = "kan391_it";
    static final UUID OWNER = UUID.fromString("a0000000-0000-4000-8000-000000000001");
    static final UUID WALLET = UUID.fromString("b0000000-0000-4000-8000-000000000002");
    static final Instant T0 = Instant.parse("2026-09-15T12:00:00Z");

    static ReceiptR2dbcStore store;
    static ReceiptService service;
    static DatabaseClient db;

    @BeforeAll
    static void migrate() {
        String host = System.getenv("CRYPTOBOT_IT_PG_HOST");
        int port = Integer.parseInt(System.getenv().getOrDefault("CRYPTOBOT_IT_PG_PORT", "5433"));
        String database = System.getenv().getOrDefault("CRYPTOBOT_IT_PG_DB", "life_engine_cryptobot");
        String user = System.getenv().getOrDefault("CRYPTOBOT_IT_PG_USER", "life_engine");
        String password = System.getenv().getOrDefault("CRYPTOBOT_IT_PG_PASSWORD", "life_engine");
        String jdbc = "jdbc:postgresql://" + host + ":" + port + "/" + database;

        // Own schema, created by Flyway on the first run and migrated to the head on every run.
        Flyway.configure().dataSource(jdbc, user, password).schemas(SCHEMA).locations("classpath:db/migration").load().migrate();

        PostgresqlConnectionFactory cf = new PostgresqlConnectionFactory(PostgresqlConnectionConfiguration.builder()
                .host(host).port(port).database(database).username(user).password(password)
                .options(Map.of("search_path", SCHEMA)).build());
        db = DatabaseClient.create(cf);
        TransactionalOperator tx = TransactionalOperator.create(new R2dbcTransactionManager(cf));
        store = new ReceiptR2dbcStore(db, new JsonDocs(new ObjectMapper()), tx);
        service = new ReceiptService(store, ReceiptSigningKey.generate("it-key"));
    }

    static ReceiptBody body(ReceiptKind kind, String nonce, List<String> parents, String tenant) {
        return new ReceiptBody(null, kind, tenant, OWNER.toString(), "it-agent@1", parents,
                List.of(new ReceiptInput(ReceiptInput.WALLET_SNAPSHOT, Digests.sha256("snapshot"))), null,
                new ReceiptBody.Model("qwen3:14b", "life-engine-runtime", null, null), null,
                new ReceiptBody.RuntimeRef(UUID.randomUUID().toString(), null, null, "it", "abc"), Map.of("locale", "en", "numCtx", 8192),
                new ReceiptBody.Output(Digests.sha256("output-of-" + nonce), "test/1", "ref:" + nonce), new ReceiptBody.Compute(10L, 2L, 16, 100L), null,
                ReproducibilityLevel.L0_SIGNED, T0, T0.plusMillis(1250), nonce, new ReceiptBody.Refs(WALLET.toString(), null, null));
    }

    @Test
    @DisplayName("V6 applied; insert/read round-trips body, canonical bytes and signature; verify passes on what Postgres gives back")
    void roundTrip() {
        String tenant = UUID.randomUUID().toString();
        String nonce = "snap-" + UUID.randomUUID();
        IntelligenceReceipt issued = service.issue(ReceiptDraft.of(body(ReceiptKind.WALLET_SNAPSHOT, nonce, List.of(), tenant))
                .withArtifact("PORTFOLIO_SNAPSHOT", "portfolio-snapshot/1", "portfolio_snapshot:" + nonce)).block();
        IntelligenceReceipt read = store.findByHash(issued.receiptHash()).block();
        assertThat(read).isNotNull();
        assertThat(read.canonicalJson()).isEqualTo(issued.canonicalJson());
        assertThat(read.body().toMap()).isEqualTo(issued.body().toMap());
        assertThat(read.signature()).isEqualTo(issued.signature());
        assertThat(read.anchor()).isNull();
        assertThat(service.verify(read).block().valid()).isTrue();
        assertThat(store.findByHashAndOwner(issued.receiptHash(), UUID.randomUUID()).block()).isNull();
        assertThat(store.findByNonce(tenant, nonce).block().receiptHash()).isEqualTo(issued.receiptHash());
        assertThat(store.findByWallet(WALLET, 500).map(IntelligenceReceipt::receiptHash).collectList().block()).contains(issued.receiptHash());
        Long artifacts = db.sql("SELECT count(*) AS n FROM artifact WHERE receipt_hash = :h").bind("h", issued.receiptHash())
                .map((row, meta) -> row.get("n", Long.class)).one().block();
        assertThat(artifacts).isEqualTo(1);
    }

    @Test
    @DisplayName("content-addressed no-op, nonce guard, FK on parents, edges in both directions")
    void invariants() {
        String tenant = UUID.randomUUID().toString();
        ReceiptBody rootBody = body(ReceiptKind.WALLET_SNAPSHOT, "snap-a", List.of(), tenant);
        IntelligenceReceipt root = service.issue(ReceiptDraft.of(rootBody)).block();
        // Same body again: ON CONFLICT (receipt_hash) DO NOTHING, the stored row comes back.
        assertThat(service.issue(ReceiptDraft.of(rootBody)).block().receiptHash()).isEqualTo(root.receiptHash());

        ReceiptBody replay = new ReceiptBody(null, ReceiptKind.WALLET_SNAPSHOT, tenant, OWNER.toString(), "other-agent@2", List.of(), List.of(), null, null, null, null,
                Map.of(), new ReceiptBody.Output(Digests.sha256("else"), "test/1", null), null, null, ReproducibilityLevel.L0_SIGNED, T0, T0, "snap-a", null);
        assertThatThrownBy(() -> service.issue(ReceiptDraft.of(replay)).block()).isInstanceOf(ControlPlaneExceptions.Conflict.class);

        IntelligenceReceipt child = service.issue(ReceiptDraft.of(body(ReceiptKind.RISK_DECISION, "risk-a", List.of(root.receiptHash()), tenant))
                .withRole(root.receiptHash(), ReceiptEdge.Role.VALIDATES)).block();
        assertThat(store.parentsOf(child.receiptHash()).collectList().block())
                .containsExactly(new ReceiptEdge(child.receiptHash(), root.receiptHash(), ReceiptEdge.Role.VALIDATES));
        assertThat(store.childrenOf(root.receiptHash()).collectList().block()).hasSize(1);
        assertThat(store.existingInTenant(List.of(root.receiptHash(), child.receiptHash(), Digests.sha256("ghost")), tenant).collectList().block())
                .containsExactlyInAnyOrder(root.receiptHash(), child.receiptHash());

        // The FK itself, bypassing the service's own check: an edge to a hash that is not there is refused by Postgres.
        IntelligenceReceipt orphan = service.seal(body(ReceiptKind.RISK_DECISION, "risk-b", List.of(), tenant));
        assertThatThrownBy(() -> store.insert(orphan, List.of(new ReceiptEdge(orphan.receiptHash(), Digests.sha256("ghost"), ReceiptEdge.Role.DERIVES_FROM)), List.of(), null).block())
                .isInstanceOf(ControlPlaneExceptions.Conflict.class);
        assertThat(store.findByHash(orphan.receiptHash()).block()).as("the transaction rolled back the receipt row too").isNull();
    }

    @Test
    @DisplayName("V7 applied (KAN-392): an L1 RISK_DECISION stores its input/output trees, and verify re-executes the engine from what Postgres gives back")
    void l1RoundTripReproduces() {
        String tenant = UUID.randomUUID().toString();
        DeterministicRiskEngine engine = DeterministicRiskEngine.v1();
        RiskInput input = new RiskInput(List.of(
                new RiskInput.PositionInput("SOL", "So11111111111111111111111111111111111111112", 7000, 700_000_000L, true, true, false),
                new RiskInput.PositionInput("USDC", "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v", 3000, 300_000_000L, true, true, true)),
                1_000_000_000L, null, null);
        DeterministicDecision d = engine.decide(input);
        String nonce = "risk-" + UUID.randomUUID();
        ReceiptBody body = new ReceiptBody(null, ReceiptKind.RISK_DECISION, tenant, OWNER.toString(), "risk-engine@" + DeterministicRiskEngine.VERSION, List.of(),
                List.of(new ReceiptInput(ReceiptInput.WALLET_SNAPSHOT, Digests.sha256("snapshot")), new ReceiptInput(ReceiptInput.RISK_INPUT, d.inputHash())),
                null, null, new ReceiptBody.Engine(d.engineId(), d.engineVersion(), d.weightsHash()), null, Map.of(),
                new ReceiptBody.Output(d.outputHash(), "risk-decision/1", "risk_decision:" + nonce), new ReceiptBody.Compute(null, null, 1, null), null,
                ReproducibilityLevel.L1_REPRODUCIBLE, T0, T0.plusMillis(3), nonce, new ReceiptBody.Refs(WALLET.toString(), null, null));
        DeterministicInference inference = DeterministicInference.unbound(tenant, d.engineId(), d.engineVersion(), d.weightsHash(), d.input().toMap(), d.output().toMap());
        IntelligenceReceipt issued = service.issue(ReceiptDraft.of(body).withInference(inference)).block();

        DeterministicInference stored = store.findInference(issued.receiptHash()).block();
        assertThat(stored).isNotNull();
        assertThat(stored.inputHash()).isEqualTo(d.inputHash());
        assertThat(stored.outputHash()).isEqualTo(d.outputHash());
        assertThat(DeterministicInference.hashOf(stored.input())).as("JSONB round-trip keeps the canonical form").isEqualTo(d.inputHash());
        assertThat(DeterministicInference.hashOf(stored.output())).isEqualTo(d.outputHash());

        ReceiptService.Verification v = service.verify(store.findByHash(issued.receiptHash()).block()).block();
        assertThat(v.valid()).isTrue();
        assertThat(v.reproduced()).isTrue();
        assertThat(v.reproduction().reason()).isEqualTo(DeterministicReproducer.REASON_OK);
        assertThat(v.reproduction().actualOutputHash()).isEqualTo(d.outputHash());
    }
}
