package io.lifeengine.cryptobot.infrastructure.persistence.controlplane;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.lifeengine.cryptobot.application.receipt.ReceiptDraft;
import io.lifeengine.cryptobot.application.receipt.ReceiptService;
import io.lifeengine.cryptobot.core.receipts.AnchorMemo;
import io.lifeengine.cryptobot.core.receipts.Digests;
import io.lifeengine.cryptobot.core.receipts.IntelligenceReceipt;
import io.lifeengine.cryptobot.core.receipts.MerkleTree;
import io.lifeengine.cryptobot.core.receipts.ReceiptAnchor;
import io.lifeengine.cryptobot.core.receipts.ReceiptBody;
import io.lifeengine.cryptobot.core.receipts.ReceiptInput;
import io.lifeengine.cryptobot.core.receipts.ReceiptKind;
import io.lifeengine.cryptobot.core.receipts.ReceiptSigningKey;
import io.lifeengine.cryptobot.core.receipts.ReproducibilityLevel;
import io.r2dbc.postgresql.PostgresqlConnectionConfiguration;
import io.r2dbc.postgresql.PostgresqlConnectionFactory;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
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
 * {@link AnchorR2dbcStore} against a real Postgres (KAN-394): {@code V8} applies on top of the
 * receipts schema, and the store keeps the contract the in-memory one promises — a root is
 * inserted once with its members, unanchored excludes what a live batch covers, stamping writes
 * the anchor columns of every member (with the proof round-tripping through JSONB) and only
 * from a FINALIZED anchor, per-owner member reads. Opt-in like {@link ReceiptR2dbcStoreIT}
 * (same env vars), in its own scratch schema {@code kan394_it} (never dropped by the test).
 */
@EnabledIfEnvironmentVariable(named = "CRYPTOBOT_IT_PG_HOST", matches = ".+")
class AnchorR2dbcStoreIT {

    /** Own scratch schema: {@code kan391_it} may carry V7 from another branch's IT, which this branch does not resolve. */
    static final String SCHEMA = "kan394_it";
    static final Instant T0 = Instant.parse("2026-09-18T03:00:00Z");

    static AnchorR2dbcStore anchors;
    static ReceiptR2dbcStore receipts;
    static ReceiptService service;

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
        DatabaseClient db = DatabaseClient.create(cf);
        TransactionalOperator tx = TransactionalOperator.create(new R2dbcTransactionManager(cf));
        JsonDocs docs = new JsonDocs(new ObjectMapper());
        receipts = new ReceiptR2dbcStore(db, docs, tx);
        anchors = new AnchorR2dbcStore(db, docs, tx);
        service = new ReceiptService(receipts, ReceiptSigningKey.generate("it-key"));
    }

    static IntelligenceReceipt issue(UUID owner, String nonce) {
        ReceiptBody body = new ReceiptBody(null, ReceiptKind.WALLET_SNAPSHOT, owner.toString(), owner.toString(), "it-agent@1", List.of(),
                List.of(new ReceiptInput(ReceiptInput.WALLET_SNAPSHOT, Digests.sha256("snapshot-" + nonce))), null, null, null, null, Map.of(),
                new ReceiptBody.Output(Digests.sha256("output-of-" + nonce), "test/1", null), null, null,
                ReproducibilityLevel.L0_SIGNED, T0, T0.plusSeconds(1), nonce, null);
        return service.issue(ReceiptDraft.of(body)).block();
    }

    @Test
    @DisplayName("V8 applied; insert once with members; unanchored excludes live batches; stamp writes chain/tx/slot/root/proof on every member; owner-scoped reads")
    void anchorRoundTrip() {
        UUID ownerA = UUID.randomUUID();
        UUID ownerB = UUID.randomUUID();
        String run = UUID.randomUUID().toString().substring(0, 8);
        List<IntelligenceReceipt> issued = List.of(issue(ownerA, "a1-" + run), issue(ownerA, "a2-" + run), issue(ownerB, "b1-" + run));
        List<String> hashes = issued.stream().map(IntelligenceReceipt::receiptHash).toList();
        assertThat(anchors.unanchoredReceiptHashes(10_000).collectList().block()).containsAll(hashes);
        long unanchoredBefore = anchors.countUnanchored().block();

        MerkleTree tree = MerkleTree.of(hashes);
        AnchorMemo memo = new AnchorMemo(tree.root(), 3, T0);
        List<ReceiptAnchor.Member> members = new ArrayList<>();
        for (String h : tree.leaves()) {
            members.add(new ReceiptAnchor.Member(tree.root(), h, tree.proofFor(h)));
        }
        ReceiptAnchor pending = ReceiptAnchor.pending(tree.root(), "solana-devnet", memo, T0);
        ReceiptAnchor stored = anchors.insert(pending, members).block();
        assertThat(stored.status()).isEqualTo(ReceiptAnchor.Status.PENDING);
        assertThat(stored.memo()).isEqualTo(memo.text());
        assertThat(stored.createdAt()).isEqualTo(T0);
        // Same root again: ON CONFLICT DO NOTHING, the stored row comes back, members are not duplicated.
        assertThat(anchors.insert(pending.failedAttempt("ignored", T0), members).block().status()).isEqualTo(ReceiptAnchor.Status.PENDING);
        assertThat(anchors.members(tree.root()).collectList().block()).hasSize(3);
        assertThat(anchors.members(tree.root()).map(ReceiptAnchor.Member::proof).collectList().block()).allMatch(p -> p.size() == 2 || p.size() == 1);

        // Covered by a live batch ⇒ not unanchored any more, but still counted as pending (no finalized anchor).
        assertThat(anchors.unanchoredReceiptHashes(10_000).collectList().block()).doesNotContainAnyElementsOf(hashes);
        assertThat(anchors.countUnanchored().block()).isEqualTo(unanchoredBefore);
        ReceiptAnchor.Member membership = anchors.membershipOf(hashes.get(0)).block();
        assertThat(membership.root()).isEqualTo(tree.root());
        assertThat(MerkleTree.verify(hashes.get(0), membership.proof(), tree.root())).isTrue();

        // Stamping is refused before FINALIZED.
        ReceiptAnchor submitted = stored.submitted("FeePayer111111111111111111111111111111111111", "5ig" + run, "So11111111111111111111111111111111111111112", 1000L, T0.plusSeconds(5));
        submitted = anchors.update(submitted).block();
        assertThat(submitted.status()).isEqualTo(ReceiptAnchor.Status.SUBMITTED);
        assertThat(submitted.attempts()).isEqualTo(1);
        assertThat(submitted.submittedAt()).isEqualTo(T0.plusSeconds(5));
        assertThat(anchors.findByStatus(ReceiptAnchor.Status.SUBMITTED, 1000).map(ReceiptAnchor::root).collectList().block()).contains(tree.root());
        ReceiptAnchor notFinal = submitted;
        assertThatThrownBy(() -> anchors.stampReceipts(notFinal).block()).isInstanceOf(IllegalStateException.class);
        assertThat(receipts.findByHash(hashes.get(0)).block().anchor()).isNull();

        ReceiptAnchor finalized = anchors.update(submitted.finalized(4242L, T0.plusSeconds(30))).block();
        assertThat(finalized.status()).isEqualTo(ReceiptAnchor.Status.FINALIZED);
        assertThat(finalized.slot()).isEqualTo(4242L);
        assertThat(anchors.stampReceipts(finalized).block()).isEqualTo(3L);
        assertThat(anchors.countUnanchored().block()).isEqualTo(unanchoredBefore - 3);
        for (IntelligenceReceipt r : issued) {
            IntelligenceReceipt read = receipts.findByHash(r.receiptHash()).block();
            assertThat(read.anchor()).isNotNull();
            assertThat(read.anchor().chain()).isEqualTo("solana-devnet");
            assertThat(read.anchor().tx()).isEqualTo("5ig" + run);
            assertThat(read.anchor().slot()).isEqualTo(4242L);
            assertThat(read.anchor().root()).isEqualTo(tree.root());
            assertThat(read.anchor().proof()).isEqualTo(tree.proofFor(r.receiptHash()));
            assertThat(MerkleTree.verify(read.receiptHash(), read.anchor().proof(), read.anchor().root())).isTrue();
            assertThat(read.canonicalJson()).as("the body and signature are untouched").isEqualTo(r.canonicalJson());
            assertThat(read.signature()).isEqualTo(r.signature());
        }
        // Idempotent: stamping again (a re-anchor after a reorg would carry a new tx) rewrites the same columns.
        ReceiptAnchor reanchored = anchors.update(finalized.failed("reorg", T0).submitted(finalized.feePayer(), "5ig2" + run, finalized.blockhash(), 1200L, T0)
                .finalized(4300L, T0)).block();
        assertThat(anchors.stampReceipts(reanchored).block()).isEqualTo(3L);
        assertThat(receipts.findByHash(hashes.get(1)).block().anchor().tx()).isEqualTo("5ig2" + run);
        assertThat(receipts.findByHash(hashes.get(1)).block().anchor().slot()).isEqualTo(4300L);

        // Owner-scoped: each owner sees only their members; the batch itself is readable and listed.
        assertThat(anchors.membersOwnedBy(tree.root(), ownerA).map(ReceiptAnchor.Member::receiptHash).collectList().block())
                .containsExactlyInAnyOrder(hashes.get(0), hashes.get(1));
        assertThat(anchors.membersOwnedBy(tree.root(), ownerB).map(ReceiptAnchor.Member::receiptHash).collectList().block()).containsExactly(hashes.get(2));
        assertThat(anchors.findByRoot(tree.root()).block().updatedAt().truncatedTo(ChronoUnit.SECONDS)).isEqualTo(T0);
        assertThat(anchors.findRecent(200).map(ReceiptAnchor::root).collectList().block()).contains(tree.root());

        // A member pointing at a receipt that does not exist is refused by the FK, and the anchor row rolls back with it.
        String ghostRoot = Digests.sha256("ghost-root-" + run);
        assertThatThrownBy(() -> anchors.insert(ReceiptAnchor.pending(ghostRoot, "solana-devnet", new AnchorMemo(ghostRoot, 1, T0), T0),
                List.of(new ReceiptAnchor.Member(ghostRoot, Digests.sha256("ghost-" + run), List.of()))).block()).isInstanceOf(Exception.class);
        assertThat(anchors.findByRoot(ghostRoot).block()).isNull();
    }

    @Test
    @DisplayName("members of an ABANDONED batch are unanchored again; membershipOf prefers the FINALIZED batch")
    void abandonedMembersAreEligibleAgain() {
        UUID owner = UUID.randomUUID();
        String run = UUID.randomUUID().toString().substring(0, 8);
        IntelligenceReceipt r = issue(owner, "x1-" + run);
        MerkleTree tree = MerkleTree.of(List.of(r.receiptHash()));
        ReceiptAnchor a = anchors.insert(ReceiptAnchor.pending(tree.root(), "solana-devnet", new AnchorMemo(tree.root(), 1, T0), T0),
                List.of(new ReceiptAnchor.Member(tree.root(), r.receiptHash(), List.of()))).block();
        assertThat(anchors.unanchoredReceiptHashes(10_000).collectList().block()).doesNotContain(r.receiptHash());
        anchors.update(a.failedAttempt("signer down", T0).abandoned("max attempts", T0)).block();
        assertThat(anchors.unanchoredReceiptHashes(10_000).collectList().block()).contains(r.receiptHash());
        assertThat(anchors.membershipOf(r.receiptHash()).block()).isNull();
    }
}
