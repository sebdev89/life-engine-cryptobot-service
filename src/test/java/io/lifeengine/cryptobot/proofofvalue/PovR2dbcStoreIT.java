package io.lifeengine.cryptobot.proofofvalue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.lifeengine.cryptobot.application.receipt.ReceiptDraft;
import io.lifeengine.cryptobot.application.receipt.ReceiptService;
import io.lifeengine.cryptobot.core.receipts.Digests;
import io.lifeengine.cryptobot.core.receipts.IntelligenceReceipt;
import io.lifeengine.cryptobot.core.receipts.ReceiptBody;
import io.lifeengine.cryptobot.core.receipts.ReceiptKind;
import io.lifeengine.cryptobot.core.receipts.ReceiptSigningKey;
import io.lifeengine.cryptobot.core.receipts.ReproducibilityLevel;
import io.lifeengine.cryptobot.infrastructure.persistence.controlplane.JsonDocs;
import io.lifeengine.cryptobot.infrastructure.persistence.controlplane.ReceiptR2dbcStore;
import io.r2dbc.postgresql.PostgresqlConnectionConfiguration;
import io.r2dbc.postgresql.PostgresqlConnectionFactory;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
 * KAN-818 persistence against a real Postgres (Testcontainers, Flyway V1..V13): the VALUE_EVENT
 * kind passes the receipt CHECK, identities are idempotent by {@code (tenant, id)} with FK owner,
 * a value event and its contributions are written in one transaction and read back with the
 * identity join, the unique {@code (tenant, value_event_hash)} and the role CHECK are the
 * database's, and everything is tenant-scoped.
 *
 * <p>Opt-in ({@code *IT}): {@code ./mvnw test -Dtest=PovR2dbcStoreIT}. Needs Docker.
 */
@Testcontainers
class PovR2dbcStoreIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
            .withDatabaseName("cryptobot_it").withUsername("cryptobot").withPassword("cryptobot");

    static PovIdentityR2dbcStore identities;
    static ValueEventR2dbcStore events;
    static KnowledgeAssetR2dbcStore assets;
    static ReceiptService receipts;
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
        identities = new PovIdentityR2dbcStore(db);
        events = new ValueEventR2dbcStore(db, tx);
        assets = new KnowledgeAssetR2dbcStore(db);
        receipts = new ReceiptService(new ReceiptR2dbcStore(db, new JsonDocs(new ObjectMapper().findAndRegisterModules()), tx),
                ReceiptSigningKey.generate("pov-it"));
    }

    @Test
    @DisplayName("identidades: idempotentes por (tenant, id), FK al owner, scope por tenant")
    void identities() {
        String tenant = UUID.randomUUID().toString();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        PovIdentity sebas = new PovIdentity(tenant, "sebas", IdentityKind.HUMAN, "Sebastián", null, null, null, now);
        assertThat(identities.insertIfAbsent(sebas).block()).isEqualTo(sebas);
        assertThat(identities.insertIfAbsent(sebas).blockOptional()).as("second insert is a no-op").isEmpty();
        PovIdentity agent = new PovIdentity(tenant, "dev-agent-17", IdentityKind.AGENT, "Dev Agent 17", null, "sebas", "sebas", now.plusMillis(1));
        identities.insertIfAbsent(agent).block();
        assertThat(identities.find(tenant, "dev-agent-17").block()).isEqualTo(agent);
        assertThat(identities.findAll(tenant).map(PovIdentity::id).collectList().block()).containsExactly("sebas", "dev-agent-17");
        assertThat(identities.findAll(tenant, List.of("dev-agent-17", "ghost")).collectList().block()).containsExactly(agent);
        assertThat(identities.find(UUID.randomUUID().toString(), "sebas").blockOptional()).isEmpty();
        assertThatThrownBy(() -> identities.insertIfAbsent(new PovIdentity(tenant, "orphan", IdentityKind.AGENT, "Orphan", null, "nobody", null, now)).block())
                .hasMessageContaining("fk_pov_identity_owner");
    }

    @Test
    @DisplayName("value event: recibo VALUE_EVENT + evento + contribuciones en una transacción; lectura con join; unique por hash; tenant")
    void valueEvents() {
        UUID owner = UUID.randomUUID();
        String tenant = owner.toString();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        identities.insertIfAbsent(new PovIdentity(tenant, "sebas", IdentityKind.HUMAN, "Sebastián", null, null, null, now)).block();
        identities.insertIfAbsent(new PovIdentity(tenant, "cryptobot-001", IdentityKind.AGENT, "CryptoBot 001", null, "sebas", null, now)).block();

        ValueEventRecord first = event(owner, "task-1", now, List.of(
                new ValueEventRecord.Contribution(0, "sebas", ContributionRole.SPECIFIER, 50, null, null),
                new ValueEventRecord.Contribution(1, "cryptobot-001", ContributionRole.OPERATOR, 50, null, null)));
        ValueEventRecord stored = events.insert(first).block();
        assertThat(stored.id()).isEqualTo(first.id());
        assertThat(stored.receiptHash()).isEqualTo(first.receiptHash());
        assertThat(stored.canonical()).isEqualTo(first.canonical());
        assertThat(stored.contributions()).extracting(ValueEventRecord.Contribution::displayName).containsExactly("Sebastián", "CryptoBot 001");
        assertThat(stored.contributions()).extracting(ValueEventRecord.Contribution::kind).containsExactly(IdentityKind.HUMAN, IdentityKind.AGENT);
        assertThat(events.findByHash(tenant, first.valueEventHash()).block().id()).isEqualTo(first.id());
        assertThat(events.find(UUID.randomUUID().toString(), first.id()).blockOptional()).as("another tenant").isEmpty();

        // The receipt stored by ReceiptService is the VALUE_EVENT one (the V12 CHECK accepts the kind).
        IntelligenceReceipt receipt = receipts.require(owner, first.receiptHash()).block();
        assertThat(receipt.kind()).isEqualTo(ReceiptKind.VALUE_EVENT);
        assertThat(receipt.body().output().hash()).isEqualTo(first.valueEventHash());

        ValueEventRecord second = event(owner, "task-2", now.plusSeconds(1), List.of(
                new ValueEventRecord.Contribution(0, "sebas", ContributionRole.REVIEWER, 100, null, null)));
        events.insert(second).block();
        assertThat(events.findRecent(tenant, 10).map(ValueEventRecord::taskId).collectList().block()).containsExactly("task-2", "task-1");
        assertThat(events.findRecent(tenant, 1).collectList().block()).hasSize(1);

        // Same content hash in the same tenant: refused by the database (the service maps it to the stored row).
        ValueEventRecord dup = new ValueEventRecord(UUID.randomUUID(), tenant, owner, second.receiptHash(), first.valueEventHash(), "p", "t", "x",
                first.artifactHash(), first.acceptanceHash(), now, DistributionPolicy.EQUAL_SPLIT_V1, 100, first.canonical(), now, List.of());
        assertThatThrownBy(() -> events.insert(dup).block()).hasMessageContaining("pov_value_event");

        // A contribution to an unknown identity rolls back the whole event (one transaction).
        ValueEventRecord bad = event(owner, "task-3", now.plusSeconds(2), List.of(
                new ValueEventRecord.Contribution(0, "sebas", ContributionRole.SPECIFIER, 50, null, null),
                new ValueEventRecord.Contribution(1, "ghost", ContributionRole.IMPLEMENTER, 50, null, null)));
        assertThatThrownBy(() -> events.insert(bad).block()).hasMessageContaining("fk_pov_contribution_identity");
        assertThat(events.find(tenant, bad.id()).blockOptional()).as("rolled back").isEmpty();
        Long rows = db.sql("SELECT count(*) AS n FROM pov_contribution WHERE value_event_id = :id").bind("id", bad.id())
                .map((r, m) -> r.get("n", Long.class)).one().block();
        assertThat(rows).isZero();
    }

    @Test
    @DisplayName("KAN-819: knowledge assets (parent_ids[], join del creator), links y compute receipts en la misma transacción; wallet backfill")
    void attribution() {
        UUID owner = UUID.randomUUID();
        String tenant = owner.toString();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        String wallet = "5ps1ihy1rNd7JeRkkxW1YU2PE3y47rbrb6DxxdnAbmHx";
        identities.insertIfAbsent(new PovIdentity(tenant, "sebas", IdentityKind.HUMAN, "Sebastián", null, null, null, now)).block();
        identities.insertIfAbsent(new PovIdentity(tenant, "compute-node-8", IdentityKind.AGENT, "Compute Node 8", null, "sebas", null, now)).block();

        // A V1 agent without wallet gets it once; a second call does not overwrite it.
        assertThat(identities.setWalletIfMissing(tenant, "compute-node-8", wallet).block().wallet()).isEqualTo(wallet);
        assertThat(identities.setWalletIfMissing(tenant, "compute-node-8", "11111111111111111111111111111111").blockOptional()).isEmpty();
        assertThat(identities.find(tenant, "compute-node-8").block().wallet()).isEqualTo(wallet);

        PovKnowledgeAsset rules = new PovKnowledgeAsset(tenant, "production-acceptance-model@1", 1, KnowledgeAssetKind.RULESET, "Production acceptance model",
                "sebas", Digests.sha256("rules"), List.of(), now, null);
        PovKnowledgeAsset strategy = new PovKnowledgeAsset(tenant, "strategy-knowledge@3", 3, KnowledgeAssetKind.STRATEGY, "Strategy knowledge",
                "sebas", Digests.sha256("strategy"), List.of("production-acceptance-model@1"), now.plusMillis(1), null);
        assertThat(assets.insertIfAbsent(rules).block().creatorDisplayName()).isEqualTo("Sebastián");
        assertThat(assets.insertIfAbsent(rules).blockOptional()).as("idempotent").isEmpty();
        assets.insertIfAbsent(strategy).block();
        assertThat(assets.find(tenant, "strategy-knowledge@3").block().parentIds()).containsExactly("production-acceptance-model@1");
        assertThat(assets.findAll(tenant).map(PovKnowledgeAsset::id).collectList().block()).containsExactly("production-acceptance-model@1", "strategy-knowledge@3");
        assertThat(assets.findAll(tenant, List.of("strategy-knowledge@3", "ghost")).map(PovKnowledgeAsset::id).collectList().block())
                .containsExactly("strategy-knowledge@3");
        assertThatThrownBy(() -> assets.insertIfAbsent(new PovKnowledgeAsset(tenant, "x@1", 1, KnowledgeAssetKind.PROMPT, "X", "nobody",
                Digests.sha256("x"), List.of(), now, null)).block()).hasMessageContaining("fk_pov_knowledge_asset_creator");

        ValueEventRecord base = event(owner, "task-k", now, List.of(
                new ValueEventRecord.Contribution(0, "sebas", ContributionRole.KNOWLEDGE_PROVIDER, 100, null, null)));
        ValueEventRecord withAttribution = base.withAttribution(base.contributions(), List.of("production-acceptance-model@1", "strategy-knowledge@3"),
                List.of(new ValueEventRecord.ComputeReceipt(UUID.randomUUID(), 0, "compute-node-8", wallet, "gpu-node-8", "claude-opus", 182_000L, 24_000L,
                        12_500L, 4_730_000L, null)));
        ValueEventRecord stored = events.insert(withAttribution).block();
        assertThat(stored.knowledgeAssetIds()).containsExactly("production-acceptance-model@1", "strategy-knowledge@3");
        assertThat(stored.computeReceipts()).hasSize(1);
        ValueEventRecord.ComputeReceipt cr = stored.computeReceipts().get(0);
        assertThat(cr.providerDisplayName()).isEqualTo("Compute Node 8");
        assertThat(cr.providerWallet()).isEqualTo(wallet);
        assertThat(cr.gpuMillis()).isEqualTo(12_500L);
        assertThat(cr.estimatedCostMicroUsd()).isEqualTo(4_730_000L);
        assertThat(assets.usage(tenant).map(u -> u.assetId() + "@" + u.valueEventId()).collectList().block())
                .containsExactly("production-acceptance-model@1@" + stored.id(), "strategy-knowledge@3@" + stored.id());
        assertThat(events.findAll(tenant).map(ValueEventRecord::id).collectList().block()).containsExactly(stored.id());
        assertThat(events.findAll(UUID.randomUUID().toString()).collectList().block()).isEmpty();

        // An unknown asset rolls back the whole event, compute receipts included.
        ValueEventRecord badBase = event(owner, "task-bad", now.plusSeconds(1), List.of(
                new ValueEventRecord.Contribution(0, "sebas", ContributionRole.SPECIFIER, 100, null, null)));
        ValueEventRecord bad = badBase.withAttribution(badBase.contributions(), List.of("ghost@1"), List.of());
        assertThatThrownBy(() -> events.insert(bad).block()).hasMessageContaining("fk_pov_value_event_knowledge_asset");
        assertThat(events.find(tenant, bad.id()).blockOptional()).isEmpty();
    }

    /** A real VALUE_EVENT receipt first (the FK), then the event row pointing at it. */
    private static ValueEventRecord event(UUID owner, String taskId, Instant createdAt, List<ValueEventRecord.Contribution> cs) {
        String tenant = owner.toString();
        String canonical = "{\"schema\":\"pov/value-event/v1\",\"taskId\":\"" + taskId + "\",\"tenantId\":\"" + tenant + "\"}";
        String hash = Digests.sha256(canonical);
        ReceiptBody body = new ReceiptBody(null, ReceiptKind.VALUE_EVENT, tenant, tenant, ValueEventService.RECEIPT_AGENT, List.of(), List.of(), null, null,
                null, null, Map.of("taskId", taskId), new ReceiptBody.Output(hash, ValueEventCanonical.SCHEMA, null), new ReceiptBody.Compute(null, null, 1, null),
                null, ReproducibilityLevel.L0_SIGNED, createdAt, createdAt, "pov:" + hash.substring(7), null);
        IntelligenceReceipt receipt = receipts.issue(ReceiptDraft.of(body)).block();
        return new ValueEventRecord(UUID.randomUUID(), tenant, owner, receipt.receiptHash(), hash, "cryptobot", taskId, "Title " + taskId,
                Digests.sha256("artifact-" + taskId), Digests.sha256("acceptance-" + taskId), createdAt, DistributionPolicy.EQUAL_SPLIT_V1, 100, canonical,
                createdAt, cs);
    }
}
