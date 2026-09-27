package io.lifeengine.cryptobot.infrastructure.persistence.controlplane;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.lifeengine.cryptobot.application.receipt.ReceiptDraft;
import io.lifeengine.cryptobot.application.receipt.ReceiptService;
import io.lifeengine.cryptobot.core.receipts.Digests;
import io.lifeengine.cryptobot.core.receipts.IntelligenceReceipt;
import io.lifeengine.cryptobot.core.receipts.ReceiptBody;
import io.lifeengine.cryptobot.core.receipts.ReceiptEdge;
import io.lifeengine.cryptobot.core.receipts.ReceiptInput;
import io.lifeengine.cryptobot.core.receipts.ReceiptKind;
import io.lifeengine.cryptobot.core.receipts.ReceiptSigningKey;
import io.lifeengine.cryptobot.core.receipts.ReproducibilityLevel;
import io.lifeengine.cryptobot.infrastructure.persistence.controlplane.LineageRepository.Direction;
import io.lifeengine.cryptobot.infrastructure.persistence.controlplane.LineageRepository.Reached;
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
 * The {@code WITH RECURSIVE} walks against a real Postgres (KAN-393): same contract as the
 * in-memory walk — roots at 0, minimum depth, depth cap, tenant boundary, degrees, edges of the
 * induced subgraph. Opt-in like {@link ReceiptR2dbcStoreIT} (same env vars, same scratch schema
 * {@code kan391_it}); every run uses a fresh tenant so re-runs never collide.
 */
@EnabledIfEnvironmentVariable(named = "CRYPTOBOT_IT_PG_HOST", matches = ".+")
class LineageR2dbcStoreIT {

    static final String SCHEMA = "kan391_it";
    static final UUID OWNER = UUID.fromString("a0000000-0000-4000-8000-000000000001");
    static final Instant T0 = Instant.parse("2026-09-18T12:00:00Z");

    static ReceiptR2dbcStore receipts;
    static LineageR2dbcStore lineage;
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
        Flyway.configure().dataSource(jdbc, user, password).schemas(SCHEMA).locations("classpath:db/migration").load().migrate();

        PostgresqlConnectionFactory cf = new PostgresqlConnectionFactory(PostgresqlConnectionConfiguration.builder()
                .host(host).port(port).database(database).username(user).password(password)
                .options(Map.of("search_path", SCHEMA)).build());
        db = DatabaseClient.create(cf);
        TransactionalOperator tx = TransactionalOperator.create(new R2dbcTransactionManager(cf));
        JsonDocs docs = new JsonDocs(new ObjectMapper());
        receipts = new ReceiptR2dbcStore(db, docs, tx);
        lineage = new LineageR2dbcStore(db, docs);
        service = new ReceiptService(receipts, ReceiptSigningKey.generate("it-key"));
    }

    static IntelligenceReceipt issue(ReceiptKind kind, String tenant, int n, List<String> parents, Map<String, ReceiptEdge.Role> roles) {
        ReceiptBody body = new ReceiptBody(null, kind, tenant, OWNER.toString(), "it-agent@1", parents,
                List.of(new ReceiptInput(ReceiptInput.WALLET_SNAPSHOT, Digests.sha256("snapshot"))), null, null, null, null, Map.of(),
                new ReceiptBody.Output(Digests.sha256(tenant + "-out-" + n), "test/1", null), new ReceiptBody.Compute(null, null, 1, null), null,
                ReproducibilityLevel.L0_SIGNED, T0.plusSeconds(n), T0.plusSeconds(n + 1), kind.name() + "-" + n, null);
        ReceiptDraft draft = ReceiptDraft.of(body);
        for (Map.Entry<String, ReceiptEdge.Role> e : roles.entrySet()) {
            draft = draft.withRole(e.getKey(), e.getValue());
        }
        return service.issue(draft).block();
    }

    static int depthOf(List<Reached> walk, IntelligenceReceipt r) {
        return walk.stream().filter(x -> x.receipt().receiptHash().equals(r.receiptHash())).findFirst().orElseThrow().depth();
    }

    @Test
    @DisplayName("ancestors / descendants / both: shortest depth, depth cap, degrees, induced edges, tenant boundary")
    void walks() {
        String tenant = UUID.randomUUID().toString();
        // snapshot → (risk, idea) → analysis → strategy (REUSES analysis) → (riskAfter VALIDATES, simulation) → execution (EXECUTES strategy)
        IntelligenceReceipt snapshot = issue(ReceiptKind.WALLET_SNAPSHOT, tenant, 0, List.of(), Map.of());
        IntelligenceReceipt risk = issue(ReceiptKind.RISK_DECISION, tenant, 1, List.of(snapshot.receiptHash()), Map.of());
        IntelligenceReceipt idea = issue(ReceiptKind.HUMAN_IDEA, tenant, 2, List.of(snapshot.receiptHash()), Map.of());
        IntelligenceReceipt analysis = issue(ReceiptKind.MARKET_ANALYSIS, tenant, 3, List.of(idea.receiptHash(), snapshot.receiptHash(), risk.receiptHash()), Map.of());
        IntelligenceReceipt strategy = issue(ReceiptKind.STRATEGY, tenant, 4, List.of(snapshot.receiptHash(), analysis.receiptHash()),
                Map.of(analysis.receiptHash(), ReceiptEdge.Role.REUSES));
        IntelligenceReceipt riskAfter = issue(ReceiptKind.RISK_DECISION, tenant, 5, List.of(strategy.receiptHash()), Map.of(strategy.receiptHash(), ReceiptEdge.Role.VALIDATES));
        IntelligenceReceipt simulation = issue(ReceiptKind.SIMULATION, tenant, 6, List.of(strategy.receiptHash()), Map.of());
        IntelligenceReceipt execution = issue(ReceiptKind.EXECUTION, tenant, 7, List.of(simulation.receiptHash(), strategy.receiptHash()),
                Map.of(strategy.receiptHash(), ReceiptEdge.Role.EXECUTES));

        List<Reached> up = lineage.walk(List.of(execution.receiptHash()), tenant, Direction.ANCESTORS, 16).collectList().block();
        assertThat(up).extracting(r -> r.receipt().receiptHash()).containsExactlyInAnyOrder(execution.receiptHash(), simulation.receiptHash(),
                strategy.receiptHash(), analysis.receiptHash(), snapshot.receiptHash(), idea.receiptHash(), risk.receiptHash());
        assertThat(depthOf(up, execution)).isZero();
        assertThat(depthOf(up, strategy)).as("EXECUTES edge beats the path through the simulation").isEqualTo(1);
        assertThat(depthOf(up, snapshot)).isEqualTo(2);
        assertThat(depthOf(up, idea)).isEqualTo(3);
        assertThat(up).extracting(Reached::depth).isSorted();
        Reached exec = up.get(0);
        assertThat(exec.parentCount()).isEqualTo(2);
        assertThat(exec.childCount()).isZero();
        Reached snap = up.stream().filter(r -> r.receipt().receiptHash().equals(snapshot.receiptHash())).findFirst().orElseThrow();
        assertThat(snap.parentCount()).isZero();
        assertThat(snap.childCount()).isEqualTo(4);
        assertThat(snap.receipt().body().toMap()).isEqualTo(snapshot.body().toMap());
        assertThat(snap.receipt().signature()).isEqualTo(snapshot.signature());

        List<Reached> down = lineage.walk(List.of(snapshot.receiptHash()), tenant, Direction.DESCENDANTS, 16).collectList().block();
        assertThat(down).hasSize(8);
        assertThat(depthOf(down, execution)).isEqualTo(2);

        List<Reached> both = lineage.walk(List.of(strategy.receiptHash()), tenant, Direction.BOTH, 16).collectList().block();
        assertThat(both).hasSize(8);
        assertThat(depthOf(both, strategy)).isZero();
        assertThat(depthOf(both, riskAfter)).isEqualTo(1);
        assertThat(depthOf(both, idea)).isEqualTo(2);

        List<Reached> capped = lineage.walk(List.of(execution.receiptHash()), tenant, Direction.ANCESTORS, 1).collectList().block();
        assertThat(capped).extracting(r -> r.receipt().receiptHash()).containsExactlyInAnyOrder(execution.receiptHash(), simulation.receiptHash(), strategy.receiptHash());
        List<Reached> zero = lineage.walk(List.of(execution.receiptHash(), riskAfter.receiptHash()), tenant, Direction.ANCESTORS, 0).collectList().block();
        assertThat(zero).hasSize(2);
        assertThat(zero).allMatch(r -> r.depth() == 0);

        List<ReceiptEdge> edges = lineage.edgesAmong(up.stream().map(r -> r.receipt().receiptHash()).toList()).collectList().block();
        assertThat(edges).hasSize(10);
        assertThat(edges).extracting(ReceiptEdge::role).contains(ReceiptEdge.Role.REUSES, ReceiptEdge.Role.EXECUTES).doesNotContain(ReceiptEdge.Role.VALIDATES);
        assertThat(lineage.edgesAmong(List.of()).collectList().block()).isEmpty();

        // Tenant boundary: the same roots under another tenant reach nothing; a foreign parent reachable by an edge is not walked into.
        assertThat(lineage.walk(List.of(execution.receiptHash()), UUID.randomUUID().toString(), Direction.ANCESTORS, 16).collectList().block()).isEmpty();
        String other = UUID.randomUUID().toString();
        IntelligenceReceipt foreign = issue(ReceiptKind.WALLET_SNAPSHOT, other, 0, List.of(), Map.of());
        db.sql("INSERT INTO receipt_edge (child_hash, parent_hash, role) VALUES (:c, :p, 'DERIVES_FROM')")
                .bind("c", strategy.receiptHash()).bind("p", foreign.receiptHash()).fetch().rowsUpdated().block();
        List<Reached> guarded = lineage.walk(List.of(execution.receiptHash()), tenant, Direction.ANCESTORS, 16).collectList().block();
        assertThat(guarded).extracting(r -> r.receipt().receiptHash()).doesNotContain(foreign.receiptHash());
        assertThat(guarded.stream().filter(r -> r.receipt().receiptHash().equals(strategy.receiptHash())).findFirst().orElseThrow().parentCount())
                .as("the degree counts every stored edge; the walk still refuses to cross").isEqualTo(3);
        assertThat(lineage.walk(List.of(execution.receiptHash()), tenant, Direction.ANCESTORS, 0).collectList().block()).hasSize(1);
    }
}
