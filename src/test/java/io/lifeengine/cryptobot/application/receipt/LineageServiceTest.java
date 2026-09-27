package io.lifeengine.cryptobot.application.receipt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.lifeengine.cryptobot.application.controlplane.ControlPlaneExceptions;
import io.lifeengine.cryptobot.core.receipts.Digests;
import io.lifeengine.cryptobot.core.receipts.IntelligenceReceipt;
import io.lifeengine.cryptobot.core.receipts.ReceiptBody;
import io.lifeengine.cryptobot.core.receipts.ReceiptEdge;
import io.lifeengine.cryptobot.core.receipts.ReceiptInput;
import io.lifeengine.cryptobot.core.receipts.ReceiptKind;
import io.lifeengine.cryptobot.core.receipts.ReceiptSigningKey;
import io.lifeengine.cryptobot.core.receipts.ReproducibilityLevel;
import io.lifeengine.cryptobot.infrastructure.persistence.controlplane.LineageRepository.Direction;
import io.lifeengine.cryptobot.testsupport.InMemoryControlPlaneRepositories;
import io.lifeengine.cryptobot.testsupport.InMemoryLineageRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The lineage walks (KAN-393, Endgame §7) on the real in-memory stores: the demo pipeline as a
 * DAG, ancestors / descendants / both, depth bound and truncation, direct neighbours, reuse, the
 * summary, and the two things that must never happen — crossing a tenant and reading a receipt
 * that is not yours.
 */
class LineageServiceTest {

    static final UUID OWNER = UUID.fromString("a0000000-0000-4000-8000-000000000001");
    static final UUID OTHER = UUID.fromString("a0000000-0000-4000-8000-000000000002");
    static final Instant T0 = Instant.parse("2026-09-15T12:00:00Z");

    private ReceiptService receipts;
    private LineageService lineage;
    private int seq;

    // The pipeline of Endgame §19: snapshot → (risk, idea) → analysis → strategy → (risk-after VALIDATES, simulation) → execution EXECUTES
    private IntelligenceReceipt snapshot;
    private IntelligenceReceipt risk;
    private IntelligenceReceipt idea;
    private IntelligenceReceipt analysis;
    private IntelligenceReceipt strategy;
    private IntelligenceReceipt riskAfter;
    private IntelligenceReceipt simulation;
    private IntelligenceReceipt execution;

    @BeforeEach
    void setUp() {
        InMemoryControlPlaneRepositories.reset();
        receipts = new ReceiptService(InMemoryControlPlaneRepositories.receipts(), ReceiptSigningKey.generate("unit-key"));
        lineage = new LineageService(new InMemoryLineageRepository(), receipts);
        snapshot = issue(ReceiptKind.WALLET_SNAPSHOT, OWNER, List.of(), Map.of());
        risk = issue(ReceiptKind.RISK_DECISION, OWNER, List.of(snapshot.receiptHash()), Map.of());
        idea = issue(ReceiptKind.HUMAN_IDEA, OWNER, List.of(snapshot.receiptHash()), Map.of());
        analysis = issue(ReceiptKind.MARKET_ANALYSIS, OWNER, List.of(idea.receiptHash(), snapshot.receiptHash(), risk.receiptHash()), Map.of());
        strategy = issue(ReceiptKind.STRATEGY, OWNER, List.of(snapshot.receiptHash(), analysis.receiptHash()), Map.of(analysis.receiptHash(), ReceiptEdge.Role.REUSES));
        riskAfter = issue(ReceiptKind.RISK_DECISION, OWNER, List.of(strategy.receiptHash()), Map.of(strategy.receiptHash(), ReceiptEdge.Role.VALIDATES));
        simulation = issue(ReceiptKind.SIMULATION, OWNER, List.of(strategy.receiptHash()), Map.of());
        execution = issue(ReceiptKind.EXECUTION, OWNER, List.of(simulation.receiptHash(), strategy.receiptHash()), Map.of(strategy.receiptHash(), ReceiptEdge.Role.EXECUTES));
    }

    private IntelligenceReceipt issue(ReceiptKind kind, UUID owner, List<String> parents, Map<String, ReceiptEdge.Role> roles) {
        int n = seq++;
        ReceiptBody.Compute compute = kind == ReceiptKind.MARKET_ANALYSIS ? new ReceiptBody.Compute(3512L, 240L, 3512 + 3 * 240, 16100L) : new ReceiptBody.Compute(null, null, 1, null);
        ReceiptBody.Model model = kind == ReceiptKind.MARKET_ANALYSIS ? new ReceiptBody.Model("qwen3:14b", "life-engine-runtime", null, null) : null;
        ReceiptBody.Engine engine = kind == ReceiptKind.RISK_DECISION ? new ReceiptBody.Engine("risk-engine", "1.0.0", Digests.sha256("weights")) : null;
        ReproducibilityLevel level = engine != null || kind == ReceiptKind.STRATEGY ? ReproducibilityLevel.L1_REPRODUCIBLE : ReproducibilityLevel.L0_SIGNED;
        ReceiptBody body = new ReceiptBody(null, kind, owner.toString(), owner.toString(), kind.name().toLowerCase() + "-agent@1", parents,
                List.of(new ReceiptInput(ReceiptInput.WALLET_SNAPSHOT, Digests.sha256("snapshot"))), null, model, engine,
                new ReceiptBody.RuntimeRef(kind == ReceiptKind.MARKET_ANALYSIS ? UUID.randomUUID().toString() : null, null, null, "t", "c"), Map.of(),
                new ReceiptBody.Output(Digests.sha256("out-" + n), kind.name().toLowerCase() + "/1", null), compute, null, level,
                T0.plusSeconds(n), T0.plusSeconds(n + 1), kind.name() + "-" + n, new ReceiptBody.Refs(UUID.randomUUID().toString(), null, null));
        ReceiptDraft draft = ReceiptDraft.of(body);
        for (Map.Entry<String, ReceiptEdge.Role> e : roles.entrySet()) {
            draft = draft.withRole(e.getKey(), e.getValue());
        }
        return receipts.issue(draft).block();
    }

    private static List<String> hashes(LineageService.Graph g) {
        return g.nodes().stream().map(LineageService.Node::receiptHash).toList();
    }

    private static int depthOf(LineageService.Graph g, IntelligenceReceipt r) {
        return g.nodes().stream().filter(n -> n.receiptHash().equals(r.receiptHash())).findFirst().orElseThrow().depth();
    }

    @Test
    @DisplayName("ancestors of the execution: the whole pipeline, each at its shortest distance, roots and lineage root identified")
    void ancestors() {
        LineageService.Graph g = lineage.lineageOf(OWNER, execution.receiptHash(), Direction.ANCESTORS, 16).block();
        assertThat(g.roots()).containsExactly(execution.receiptHash());
        assertThat(hashes(g)).containsExactlyInAnyOrder(execution.receiptHash(), simulation.receiptHash(), strategy.receiptHash(), snapshot.receiptHash(),
                analysis.receiptHash(), idea.receiptHash(), risk.receiptHash());
        assertThat(hashes(g)).as("riskAfter is a sibling, not an ancestor").doesNotContain(riskAfter.receiptHash());
        assertThat(depthOf(g, execution)).isZero();
        assertThat(depthOf(g, simulation)).isEqualTo(1);
        assertThat(depthOf(g, strategy)).as("reached directly (EXECUTES) and via the simulation: the shortest wins").isEqualTo(1);
        assertThat(depthOf(g, analysis)).isEqualTo(2);
        assertThat(depthOf(g, snapshot)).as("directly under the strategy, not only under the analysis").isEqualTo(2);
        assertThat(depthOf(g, idea)).isEqualTo(3);
        assertThat(g.lineageRoots()).containsExactly(snapshot.receiptHash());
        assertThat(g.truncated()).isFalse();
        assertThat(g.edges()).hasSize(10); // every edge of the store except riskAfter→strategy
        assertThat(g.edges()).extracting(ReceiptEdge::role).contains(ReceiptEdge.Role.REUSES, ReceiptEdge.Role.EXECUTES).doesNotContain(ReceiptEdge.Role.VALIDATES);
        // Ordered by depth first, so a client can stream the graph outwards.
        List<Integer> depths = g.nodes().stream().map(LineageService.Node::depth).toList();
        assertThat(depths).isSorted();
    }

    @Test
    @DisplayName("descendants of the snapshot: everything; both directions from the strategy: everything")
    void descendantsAndBoth() {
        LineageService.Graph down = lineage.lineageOf(OWNER, snapshot.receiptHash(), Direction.DESCENDANTS, 16).block();
        assertThat(down.nodes()).hasSize(8);
        assertThat(depthOf(down, execution)).as("snapshot→strategy→execution beats snapshot→…→simulation→execution").isEqualTo(2);
        assertThat(down.edges()).hasSize(11);
        assertThat(down.lineageRoots()).containsExactly(snapshot.receiptHash());

        LineageService.Graph both = lineage.lineageOf(OWNER, strategy.receiptHash(), Direction.BOTH, 16).block();
        assertThat(both.nodes()).hasSize(8);
        assertThat(depthOf(both, strategy)).isZero();
        assertThat(depthOf(both, snapshot)).isEqualTo(1);
        assertThat(depthOf(both, riskAfter)).isEqualTo(1);
        assertThat(depthOf(both, idea)).isEqualTo(2);
    }

    @Test
    @DisplayName("depth is a hard bound and the graph says when it cut the walk short")
    void depthBound() {
        LineageService.Graph one = lineage.lineageOf(OWNER, execution.receiptHash(), Direction.ANCESTORS, 1).block();
        assertThat(hashes(one)).containsExactlyInAnyOrder(execution.receiptHash(), simulation.receiptHash(), strategy.receiptHash());
        assertThat(one.truncated()).as("the strategy at depth 1 still has parents outside the graph").isTrue();
        assertThat(one.maxDepth()).isEqualTo(1);
        assertThat(one.lineageRoots()).isEmpty();

        LineageService.Graph zero = lineage.lineageOf(OWNER, execution.receiptHash(), Direction.ANCESTORS, 0).block();
        assertThat(zero.nodes()).hasSize(1);
        assertThat(zero.edges()).isEmpty();
        assertThat(zero.truncated()).isTrue();
        assertThat(zero.nodes().get(0).parentCount()).isEqualTo(2);

        LineageService.Graph capped = lineage.lineageOf(OWNER, execution.receiptHash(), Direction.ANCESTORS, 10_000).block();
        assertThat(capped.maxDepth()).isEqualTo(64);
        LineageService.Graph defaulted = lineage.lineageOf(OWNER, execution.receiptHash(), Direction.ANCESTORS, -5).block();
        assertThat(defaulted.maxDepth()).isEqualTo(LineageService.DEFAULT_DEPTH);
    }

    @Test
    @DisplayName("parents / children / reusedBy are the direct neighbours with the role of their edge")
    void neighbours() {
        List<LineageService.Neighbour> parents = lineage.parents(OWNER, execution.receiptHash()).collectList().block();
        assertThat(parents).extracting(n -> n.node().receiptHash()).containsExactlyInAnyOrder(simulation.receiptHash(), strategy.receiptHash());
        assertThat(parents).filteredOn(n -> n.node().receiptHash().equals(strategy.receiptHash())).extracting(LineageService.Neighbour::role)
                .containsExactly(ReceiptEdge.Role.EXECUTES);
        assertThat(parents).filteredOn(n -> n.node().receiptHash().equals(simulation.receiptHash())).extracting(LineageService.Neighbour::role)
                .containsExactly(ReceiptEdge.Role.DERIVES_FROM);

        List<LineageService.Neighbour> children = lineage.children(OWNER, strategy.receiptHash()).collectList().block();
        assertThat(children).extracting(n -> n.node().receiptHash()).containsExactlyInAnyOrder(riskAfter.receiptHash(), simulation.receiptHash(), execution.receiptHash());
        assertThat(children).extracting(LineageService.Neighbour::role).containsExactlyInAnyOrder(ReceiptEdge.Role.VALIDATES, ReceiptEdge.Role.DERIVES_FROM, ReceiptEdge.Role.EXECUTES);

        List<LineageService.Neighbour> reused = lineage.reusedBy(OWNER, analysis.receiptHash()).collectList().block();
        assertThat(reused).hasSize(1);
        assertThat(reused.get(0).role()).isEqualTo(ReceiptEdge.Role.REUSES);
        assertThat(reused.get(0).node().kind()).isEqualTo(ReceiptKind.STRATEGY);
        assertThat(lineage.reusedBy(OWNER, snapshot.receiptHash()).collectList().block()).isEmpty();
        assertThat(lineage.parents(OWNER, snapshot.receiptHash()).collectList().block()).isEmpty();
    }

    @Test
    @DisplayName("a node carries hash, producer, level, measured compute and anchor — read from the receipt, never invented")
    void nodeContents() {
        LineageService.Graph g = lineage.lineageOf(OWNER, execution.receiptHash(), Direction.ANCESTORS, 16).block();
        LineageService.Node llm = g.nodes().stream().filter(n -> n.kind() == ReceiptKind.MARKET_ANALYSIS).findFirst().orElseThrow();
        assertThat(llm.model().ref()).isEqualTo("qwen3:14b");
        assertThat(llm.engine()).isNull();
        assertThat(llm.compute().inputTokens()).isEqualTo(3512);
        assertThat(llm.compute().units()).isEqualTo(3512 + 3 * 240);
        assertThat(llm.cost()).as("no price table ⇒ no cost claimed").isNull();
        assertThat(llm.level()).isEqualTo(ReproducibilityLevel.L0_SIGNED);
        assertThat(llm.runId()).isNotNull();
        assertThat(llm.anchor()).as("not anchored yet (KAN-394)").isNull();
        assertThat(llm.parentCount()).isEqualTo(3);
        assertThat(llm.childCount()).isEqualTo(1);
        assertThat(llm.keyId()).isEqualTo("unit-key");
        LineageService.Node det = g.nodes().stream().filter(n -> n.kind() == ReceiptKind.RISK_DECISION).findFirst().orElseThrow();
        assertThat(det.engine().id()).isEqualTo("risk-engine");
        assertThat(det.engine().weightsHash()).matches("sha256:[0-9a-f]{64}");
        assertThat(det.level()).isEqualTo(ReproducibilityLevel.L1_REPRODUCIBLE);

        LineageService.Summary s = g.summary();
        assertThat(s.nodes()).isEqualTo(7);
        assertThat(s.edges()).isEqualTo(10);
        assertThat(s.inputTokens()).isEqualTo(3512);
        assertThat(s.outputTokens()).isEqualTo(240);
        assertThat(s.computeUnits()).isEqualTo(3512 + 3 * 240 + 6);
        assertThat(s.costUsd()).isNull();
        assertThat(s.anchored()).isZero();
        assertThat(s.reused()).isEqualTo(1);
        assertThat(s.byLevel()).containsEntry("L0_SIGNED", 5).containsEntry("L1_REPRODUCIBLE", 2);
        assertThat(s.byKind()).containsEntry("RISK_DECISION", 1).containsEntry("EXECUTION", 1);
    }

    @Test
    @DisplayName("the anchor gets an explorer link when it is a Solana anchor; the summary sums cost only across one price table")
    void anchorsAndCost() {
        LineageService.Node.Anchor devnet = LineageService.Node.anchorOf(new IntelligenceReceipt.Anchor("solana-devnet", "5xSig", 123L, Digests.sha256("root"), List.of()));
        assertThat(devnet.explorerUrl()).isEqualTo("https://explorer.solana.com/tx/5xSig?cluster=devnet");
        LineageService.Node.Anchor mainnet = LineageService.Node.anchorOf(new IntelligenceReceipt.Anchor("solana-mainnet-beta", "5xSig", 1L, null, List.of()));
        assertThat(mainnet.explorerUrl()).isEqualTo("https://explorer.solana.com/tx/5xSig");
        assertThat(LineageService.Node.anchorOf(new IntelligenceReceipt.Anchor("other-chain", "t", 1L, null, List.of())).explorerUrl()).isNull();
        assertThat(LineageService.Node.anchorOf(null)).isNull();

        LineageService.Node priced = node(new ReceiptBody.Cost("0.0012", "2026-09"));
        LineageService.Node priced2 = node(new ReceiptBody.Cost("0.0019", "2026-09"));
        LineageService.Node otherTable = node(new ReceiptBody.Cost("1", "2026-10"));
        assertThat(LineageService.summarise(List.of(priced, priced2), List.of()).costUsd()).isEqualTo("0.0031");
        assertThat(LineageService.summarise(List.of(priced, priced2), List.of()).priceTableVersion()).isEqualTo("2026-09");
        assertThat(LineageService.summarise(List.of(priced, otherTable), List.of()).costUsd()).as("two tables ⇒ no total").isNull();
        assertThat(LineageService.summarise(List.of(), List.of()).costUsd()).isNull();
    }

    private static LineageService.Node node(ReceiptBody.Cost cost) {
        return new LineageService.Node(Digests.sha256(cost.usd()), ReceiptKind.MARKET_ANALYSIS, "a", ReproducibilityLevel.L0_SIGNED, 0, T0, T0, T0,
                null, null, null, cost, null, null, Digests.sha256("o"), "x/1", "k", 0, 0, null);
    }

    @Test
    @DisplayName("another owner gets a 404 for a receipt, and a walk never crosses a tenant")
    void tenancy() {
        assertThatThrownBy(() -> lineage.lineageOf(OTHER, execution.receiptHash(), Direction.ANCESTORS, 16).block())
                .isInstanceOf(ControlPlaneExceptions.NotFound.class);
        assertThatThrownBy(() -> lineage.parents(OTHER, execution.receiptHash()).collectList().block())
                .isInstanceOf(ControlPlaneExceptions.NotFound.class);
        assertThatThrownBy(() -> lineage.lineageOf(OWNER, "not-a-hash", Direction.ANCESTORS, 16).block())
                .isInstanceOf(ControlPlaneExceptions.InvalidRequest.class);

        // A foreign receipt whose edge somehow points into this tenant's graph (bypassing the service) is not walked into.
        IntelligenceReceipt foreign = issue(ReceiptKind.WALLET_SNAPSHOT, OTHER, List.of(), Map.of());
        InMemoryControlPlaneRepositories.EDGES.add(new ReceiptEdge(strategy.receiptHash(), foreign.receiptHash(), ReceiptEdge.Role.DERIVES_FROM));
        LineageService.Graph g = lineage.lineageOf(OWNER, execution.receiptHash(), Direction.ANCESTORS, 16).block();
        assertThat(hashes(g)).doesNotContain(foreign.receiptHash());
        assertThat(g.edges()).noneMatch(e -> e.parentHash().equals(foreign.receiptHash()));
        assertThat(g.truncated()).as("the strategy's parentCount now exceeds what the graph holds, but it is not on the last layer").isFalse();
        assertThat(lineage.parents(OWNER, strategy.receiptHash()).collectList().block()).extracting(n -> n.node().receiptHash()).doesNotContain(foreign.receiptHash());
    }

    @Test
    @DisplayName("proposal lineage: the proposal's own receipts at depth 0, their ancestry above; a proposal without receipts is an empty graph")
    void proposalLineage() {
        // Re-issue the proposal's receipts under a proposal id so findByProposal sees them.
        UUID proposalId = UUID.randomUUID();
        ReceiptBody withProposal = new ReceiptBody(null, ReceiptKind.STRATEGY, OWNER.toString(), OWNER.toString(), "strategy-agent@1", List.of(analysis.receiptHash()),
                List.of(), null, null, new ReceiptBody.Engine("rebalance-planner", "1.0.0", null), null, Map.of(),
                new ReceiptBody.Output(Digests.sha256("plan"), "rebalance-plan/1", null), new ReceiptBody.Compute(null, null, 1, null), null,
                ReproducibilityLevel.L1_REPRODUCIBLE, T0.plusSeconds(100), T0.plusSeconds(101), "strategy-p", new ReceiptBody.Refs(null, proposalId.toString(), null));
        IntelligenceReceipt s = receipts.issue(ReceiptDraft.of(withProposal).withRole(analysis.receiptHash(), ReceiptEdge.Role.REUSES)).block();
        LineageService.Graph g = lineage.lineageOfProposal(proposalId, OWNER.toString(), Direction.ANCESTORS, 16).block();
        assertThat(g.roots()).containsExactly(s.receiptHash());
        assertThat(hashes(g)).containsExactlyInAnyOrder(s.receiptHash(), analysis.receiptHash(), idea.receiptHash(), snapshot.receiptHash(), risk.receiptHash());
        assertThat(g.summary().reused()).isEqualTo(1);
        assertThat(g.lineageRoots()).containsExactly(snapshot.receiptHash());

        LineageService.Graph empty = lineage.lineageOfProposal(UUID.randomUUID(), OWNER.toString(), Direction.ANCESTORS, 16).block();
        assertThat(empty.nodes()).isEmpty();
        assertThat(empty.roots()).isEmpty();
        assertThat(empty.summary().nodes()).isZero();
    }

    @Test
    @DisplayName("direction parsing: case-insensitive, default ancestors, anything else is a 400")
    void directionParsing() {
        assertThat(LineageService.direction(null)).isEqualTo(Direction.ANCESTORS);
        assertThat(LineageService.direction(" Descendants ")).isEqualTo(Direction.DESCENDANTS);
        assertThat(LineageService.direction("both")).isEqualTo(Direction.BOTH);
        assertThatThrownBy(() -> LineageService.direction("sideways")).isInstanceOf(ControlPlaneExceptions.InvalidRequest.class);
    }
}
