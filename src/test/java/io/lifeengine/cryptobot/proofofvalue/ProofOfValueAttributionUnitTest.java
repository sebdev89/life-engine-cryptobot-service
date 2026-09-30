package io.lifeengine.cryptobot.proofofvalue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.lifeengine.cryptobot.core.receipts.Digests;
import io.lifeengine.cryptobot.proofofvalue.ProofOfValueDtos.AcceptanceRequest;
import io.lifeengine.cryptobot.proofofvalue.ProofOfValueDtos.ArtifactRequest;
import io.lifeengine.cryptobot.proofofvalue.ProofOfValueDtos.ComputeReceiptRequest;
import io.lifeengine.cryptobot.proofofvalue.ProofOfValueDtos.ContributionRequest;
import io.lifeengine.cryptobot.proofofvalue.ProofOfValueDtos.LedgerRowView;
import io.lifeengine.cryptobot.proofofvalue.ProofOfValueDtos.LedgerView;
import io.lifeengine.cryptobot.proofofvalue.ProofOfValueDtos.ReputationView;
import io.lifeengine.cryptobot.proofofvalue.ProofOfValueDtos.ValueEventRequest;
import io.lifeengine.cryptobot.solana.rpc.Base58;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** KAN-819: the pure parts of V2–V4 + V6 — provenance contribution, canonical v2, reputation and the ledger folds. */
class ProofOfValueAttributionUnitTest {

    static final String TENANT = "a0000000-0000-4000-8000-000000000001";
    static final String COMMIT = "3fb620dea17f6a7409e15337f5e074fea3097842";
    static final Instant T1 = Instant.parse("2026-09-30T10:00:00Z");
    static final String W_COMPUTE = "5ps1ihy1rNd7JeRkkxW1YU2PE3y47rbrb6DxxdnAbmHx";

    static final PovKnowledgeAsset RULES = asset("production-acceptance-model@1", 1, KnowledgeAssetKind.RULESET, "sebas");
    static final PovKnowledgeAsset STRATEGY = asset("strategy-knowledge@3", 3, KnowledgeAssetKind.STRATEGY, "sebas");
    static final PovKnowledgeAsset PROMPT = asset("review-prompt@2", 2, KnowledgeAssetKind.PROMPT, "review-agent-3");

    @Test
    @DisplayName("V2 wallet: Base58 de exactamente 32 bytes")
    void walletIsA32BytePublicKey() {
        assertThat(Base58.isPublicKey(W_COMPUTE)).isTrue();
        assertThat(Base58.isPublicKey("11111111111111111111111111111111")).isTrue();
        assertThat(Base58.isPublicKey("hBxVhPQ8E4i2LegsKLvezqUWNt1atk4gw3hJohmLKh")).as("31 bytes").isFalse();
        assertThat(Base58.isPublicKey("0".repeat(44))).isFalse();
    }

    @Test
    @DisplayName("V3 provenance: el creator de un asset que no es KNOWLEDGE_PROVIDER se agrega antes del reparto, con derivedFrom")
    void creatorOfAnAssetIsAddedAsKnowledgeProvider() {
        ValueEventService.Draft d = ValueEventService.draft(TENANT, request(List.of(c("sebas", ContributionRole.SPECIFIER),
                c("dev-agent-17", ContributionRole.IMPLEMENTER), c("review-agent-3", ContributionRole.REVIEWER)), List.of(RULES, STRATEGY, PROMPT), List.of()),
                List.of(RULES, STRATEGY, PROMPT), Map.of());
        assertThat(d.contributions()).extracting(x -> x.identityId() + ":" + x.role() + ":" + x.units()).containsExactly(
                "sebas:SPECIFIER:20", "dev-agent-17:IMPLEMENTER:20", "review-agent-3:REVIEWER:20",
                "sebas:KNOWLEDGE_PROVIDER:20", "review-agent-3:KNOWLEDGE_PROVIDER:20");
        assertThat(d.contributions().get(3).derivedFrom()).containsExactly("production-acceptance-model@1", "strategy-knowledge@3");
        assertThat(d.contributions().get(4).derivedFrom()).containsExactly("review-prompt@2");
        assertThat(d.contributions().stream().mapToInt(ValueEventCanonical.Contribution::units).sum()).isEqualTo(100);
        assertThat(d.schema()).isEqualTo(ValueEventCanonical.SCHEMA_V2);
        assertThat(d.canonical()).contains("\"schema\":\"pov/value-event/v2\"")
                .contains("{\"contentHash\":\"" + RULES.contentHash() + "\",\"creatorId\":\"sebas\",\"id\":\"production-acceptance-model@1\","
                        + "\"kind\":\"RULESET\",\"title\":\"production-acceptance-model@1 title\",\"version\":1}");
        assertThat(d.valueEventHash()).isEqualTo(Digests.sha256(d.canonical()));
    }

    @Test
    @DisplayName("V3 provenance: un creator ya acreditado como KNOWLEDGE_PROVIDER no se duplica")
    void explicitKnowledgeProviderIsNotDuplicated() {
        ValueEventService.Draft d = ValueEventService.draft(TENANT, request(List.of(c("dev-agent-17", ContributionRole.IMPLEMENTER),
                c("sebas", ContributionRole.KNOWLEDGE_PROVIDER)), List.of(RULES), List.of()), List.of(RULES), Map.of());
        assertThat(d.contributions()).extracting(x -> x.identityId() + ":" + x.role() + ":" + x.units())
                .containsExactly("dev-agent-17:IMPLEMENTER:50", "sebas:KNOWLEDGE_PROVIDER:50");
        assertThat(d.contributions()).extracting(ValueEventCanonical.Contribution::derivedFrom).containsOnlyNulls();
    }

    @Test
    @DisplayName("V4: el compute receipt entra al canónico con la wallet del provider y GPU en ms; no cambia el reparto")
    void computeReceiptIsCommittedButDoesNotTakeUnits() {
        PovIdentity node = new PovIdentity(TENANT, "compute-node-8", IdentityKind.AGENT, "Compute Node 8", W_COMPUTE, null, null, T1);
        ComputeReceiptRequest r = new ComputeReceiptRequest("compute-node-8", "gpu-node-8", "claude-opus", 182_000L, 24_000L, new BigDecimal("12.5"), 4_730_000L);
        List<ContributionRequest> cs = List.of(c("sebas", ContributionRole.SPECIFIER), c("dev-agent-17", ContributionRole.IMPLEMENTER));
        ValueEventService.Draft with = ValueEventService.draft(TENANT, request(cs, List.of(), List.of(r)), List.of(), Map.of("compute-node-8", node));
        ValueEventService.Draft without = ValueEventService.draft(TENANT, request(cs, List.of(), List.of()));
        assertThat(with.contributions()).isEqualTo(without.contributions());
        assertThat(with.valueEventHash()).isNotEqualTo(without.valueEventHash());
        assertThat(with.canonical()).contains("\"computeReceipts\":[{\"estimatedCostMicroUsd\":4730000,\"gpuMillis\":12500,\"inputTokens\":182000,"
                + "\"model\":\"claude-opus\",\"node\":\"gpu-node-8\",\"outputTokens\":24000,\"providerId\":\"compute-node-8\",\"providerWallet\":\"" + W_COMPUTE + "\"}]");
        assertThat(with.schema()).isEqualTo(ValueEventCanonical.SCHEMA_V2);
        assertThatThrownBy(() -> ValueEventService.draft(TENANT, request(cs, List.of(), List.of(r))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("compute-node-8");
    }

    @Test
    @DisplayName("V1 intacto: sin assets ni compute el canónico es pov/value-event/v1 con listas vacías y sin derivedFrom")
    void v1EventKeepsItsCanonicalShape() {
        ValueEventService.Draft d = ValueEventService.draft(TENANT, request(List.of(c("sebas", ContributionRole.SPECIFIER)), List.of(), List.of()));
        assertThat(d.schema()).isEqualTo(ValueEventCanonical.SCHEMA);
        assertThat(d.canonical()).contains("\"computeReceipts\":[]").contains("\"knowledgeAssets\":[]")
                .contains("\"contributions\":[{\"identityId\":\"sebas\",\"role\":\"SPECIFIER\",\"units\":100}]").doesNotContain("derivedFrom");
    }

    @Test
    @DisplayName("V2 reputación: outcomes distintos, units acumuladas, primera y última aceptación")
    void reputationIsExplicitCounts() {
        ValueEventRecord e1 = record("cryptobot", T1, List.of("production-acceptance-model@1"), cs("sebas", ContributionRole.SPECIFIER, 40,
                "dev-agent-17", ContributionRole.IMPLEMENTER, 30, "sebas", ContributionRole.KNOWLEDGE_PROVIDER, 30));
        ValueEventRecord e2 = record("portfolio", T1.plusSeconds(3600), List.of(), cs("dev-agent-17", ContributionRole.IMPLEMENTER, 100));
        Map<String, ReputationView> r = AttributionReadModel.reputations(List.of(e1, e2));
        assertThat(r.get("sebas")).isEqualTo(new ReputationView(1, 70, T1, T1));
        assertThat(r.get("dev-agent-17")).isEqualTo(new ReputationView(2, 130, T1, T1.plusSeconds(3600)));
        assertThat(r).doesNotContainKey("compute-node-8");
    }

    @Test
    @DisplayName("V6 ledger: por identidad, asset y proyecto las filas suman exactamente las units de todos los eventos")
    void ledgerAlwaysAddsUpToEveryUnit() {
        ValueEventRecord e1 = record("cryptobot", T1, List.of("production-acceptance-model@1", "strategy-knowledge@3", "review-prompt@2"),
                cs("sebas", ContributionRole.SPECIFIER, 21, "dev-agent-17", ContributionRole.IMPLEMENTER, 20, "review-agent-3", ContributionRole.REVIEWER, 20,
                        "sebas", ContributionRole.KNOWLEDGE_PROVIDER, 21, "review-agent-3", ContributionRole.KNOWLEDGE_PROVIDER, 18));
        ValueEventRecord e2 = record("cryptobot", T1.plusSeconds(60), List.of(), cs("dev-agent-17", ContributionRole.IMPLEMENTER, 34,
                "sebas", ContributionRole.KNOWLEDGE_PROVIDER, 33, "cryptobot-001", ContributionRole.OPERATOR, 33));
        ValueEventRecord e3 = record("portfolio", T1.plusSeconds(120), List.of(), cs("sebas", ContributionRole.SPECIFIER, 100));
        List<ValueEventRecord> all = List.of(e1, e2, e3);
        Map<String, PovIdentity> ids = new LinkedHashMap<>();
        for (String id : List.of("sebas", "dev-agent-17", "review-agent-3", "cryptobot-001")) {
            ids.put(id, new PovIdentity(TENANT, id, id.equals("sebas") ? IdentityKind.HUMAN : IdentityKind.AGENT, id.toUpperCase(), null, null, null, T1));
        }
        Map<String, PovKnowledgeAsset> assets = Map.of(RULES.id(), RULES, STRATEGY.id(), STRATEGY, PROMPT.id(), PROMPT);

        for (String g : AttributionReadModel.GROUP_BY) {
            LedgerView l = AttributionReadModel.ledger(g, all, ids, assets);
            assertThat(l.totalUnits()).as(g).isEqualTo(300);
            assertThat(l.rows().stream().mapToLong(LedgerRowView::totalUnits).sum()).as(g).isEqualTo(300);
        }
        assertThat(rows(AttributionReadModel.ledger("identity", all, ids, assets)))
                .containsExactly("sebas=175/3", "dev-agent-17=54/2", "review-agent-3=38/1", "cryptobot-001=33/1");
        // sebas's 21 KP units → his two assets 11/10; review-agent-3's 18 → its prompt; e2's KP has no asset → unattributed.
        assertThat(rows(AttributionReadModel.ledger("asset", all, ids, assets)))
                .containsExactly("review-prompt@2=18/1", "production-acceptance-model@1=11/1", "strategy-knowledge@3=10/1", "unattributed=261/3");
        assertThat(rows(AttributionReadModel.ledger("project", all, ids, assets))).containsExactly("cryptobot=200/2", "portfolio=100/1");
        assertThat(AttributionReadModel.ledger("identity", List.of(), ids, assets).rows()).isEmpty();
        assertThat(Arrays.stream(AttributionReadModel.split(21, 2)).boxed().toList()).containsExactly(11L, 10L);
    }

    private static List<String> rows(LedgerView l) {
        return l.rows().stream().map(r -> r.key() + "=" + r.totalUnits() + "/" + r.acceptedOutcomes()).toList();
    }

    private static List<ValueEventRecord.Contribution> cs(Object... triples) {
        List<ValueEventRecord.Contribution> out = new java.util.ArrayList<>();
        for (int i = 0; i < triples.length; i += 3) {
            out.add(new ValueEventRecord.Contribution(i / 3, (String) triples[i], (ContributionRole) triples[i + 1], (Integer) triples[i + 2], null, null));
        }
        return out;
    }

    private static ValueEventRecord record(String project, Instant acceptedAt, List<String> assetIds, List<ValueEventRecord.Contribution> cs) {
        int total = cs.stream().mapToInt(ValueEventRecord.Contribution::units).sum();
        return new ValueEventRecord(UUID.randomUUID(), TENANT, UUID.fromString(TENANT), "sha256:" + "a".repeat(64), "sha256:" + "b".repeat(64), project,
                "task", "title", "sha256:" + "c".repeat(64), "sha256:" + "d".repeat(64), acceptedAt, DistributionPolicy.EQUAL_SPLIT_V1, total, "{}",
                acceptedAt, cs, assetIds, List.of());
    }

    private static PovKnowledgeAsset asset(String id, int version, KnowledgeAssetKind kind, String creator) {
        return new PovKnowledgeAsset(TENANT, id, version, kind, id + " title", creator, Digests.sha256(id), List.of(), T1, null);
    }

    private static ContributionRequest c(String id, ContributionRole role) {
        return new ContributionRequest(id, role);
    }

    private static ValueEventRequest request(List<ContributionRequest> cs, List<PovKnowledgeAsset> assets, List<ComputeReceiptRequest> compute) {
        Map<String, Boolean> stages = new LinkedHashMap<>();
        AcceptancePolicy.STAGES.forEach(s -> stages.put(s, true));
        return new ValueEventRequest("cryptobot", "KAN-819", "Improve CryptoBot opportunity detection", new ArtifactRequest(COMMIT, null, null),
                new AcceptanceRequest("release-truth", "uat-k8s", stages, null, T1), cs, assets.stream().map(PovKnowledgeAsset::id).toList(), compute, null);
    }
}
