package io.lifeengine.cryptobot.application.receipt;

import io.lifeengine.cryptobot.adapters.solana.SolanaCluster;
import io.lifeengine.cryptobot.domain.receipt.IntelligenceReceipt;
import io.lifeengine.cryptobot.domain.receipt.ReceiptBody;
import io.lifeengine.cryptobot.domain.receipt.ReceiptEdge;
import io.lifeengine.cryptobot.domain.receipt.ReceiptKind;
import io.lifeengine.cryptobot.domain.receipt.ReproducibilityLevel;
import io.lifeengine.cryptobot.infrastructure.persistence.controlplane.LineageRepository;
import io.lifeengine.cryptobot.infrastructure.persistence.controlplane.LineageRepository.Direction;
import io.lifeengine.cryptobot.infrastructure.persistence.controlplane.LineageRepository.Reached;
import io.lifeengine.cryptobot.observability.CryptobotMetrics;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Read side of the provenance DAG (KAN-393, Endgame §7 and §15): {@code ancestors},
 * {@code descendants}, direct {@code parents}/{@code children}, {@code reusedBy}, and the lineage
 * of a whole proposal — what the UI draws. Every walk starts from receipts the caller owns and
 * never leaves the caller's tenant; the tenant comes from the JWT principal, never from a header.
 *
 * <p>What a node carries is what a judge needs to see on a slide and what an auditor needs to
 * re-verify: hash, kind, agent, model or engine, measured compute, cost (when priced), level,
 * anchor. Never a prompt, an answer, a chunk or a key — the body holds none of those to begin with.
 */
@Service
public class LineageService {

    /** Default and hard limits on how far a walk goes. The pipeline of one proposal is ~6 deep. */
    public static final int DEFAULT_DEPTH = 16;

    /** One receipt as a node of the graph: identity, producer, compute, level, anchor — all from the stored receipt. */
    public record Node(
            String receiptHash,
            ReceiptKind kind,
            String agentId,
            ReproducibilityLevel level,
            int depth,
            Instant createdAt,
            Instant startedAt,
            Instant completedAt,
            ReceiptBody.Model model,
            ReceiptBody.Engine engine,
            ReceiptBody.Compute compute,
            ReceiptBody.Cost cost,
            Anchor anchor,
            String runId,
            String outputHash,
            String outputSchema,
            String keyId,
            int parentCount,
            int childCount,
            ReceiptBody.Refs refs) {

        /** The stored anchor plus the explorer link a judge clicks. */
        public record Anchor(String chain, String tx, Long slot, String root, String explorerUrl) {}

        static Node of(Reached r) {
            IntelligenceReceipt receipt = r.receipt();
            ReceiptBody b = receipt.body();
            return new Node(receipt.receiptHash(), b.kind(), b.agentId(), b.reproducibility(), r.depth(), receipt.createdAt(), b.startedAt(), b.completedAt(),
                    b.model(), b.engine(), b.compute(), b.cost(), anchorOf(receipt.anchor()), b.runtime() == null ? null : b.runtime().runId(),
                    b.output().hash(), b.output().schema(), receipt.signature() == null ? null : receipt.signature().keyId(),
                    r.parentCount(), r.childCount(), b.refs());
        }

        static Anchor anchorOf(IntelligenceReceipt.Anchor a) {
            if (a == null) {
                return null;
            }
            String chain = a.chain() == null ? "" : a.chain().toLowerCase(Locale.ROOT);
            String explorer = null;
            if (chain.startsWith("solana") && a.tx() != null) {
                explorer = (chain.endsWith("devnet") ? SolanaCluster.DEVNET : SolanaCluster.MAINNET_BETA).explorerTxUrl(a.tx());
            }
            return new Anchor(a.chain(), a.tx(), a.slot(), a.root(), explorer);
        }
    }

    /** Totals over the nodes returned: measured compute, cost only when every priced node shares a price table, anchors, reuse. */
    public record Summary(
            int nodes,
            int edges,
            long computeUnits,
            long inputTokens,
            long outputTokens,
            String costUsd,
            String priceTableVersion,
            int anchored,
            int reused,
            Map<String, Integer> byLevel,
            Map<String, Integer> byKind) {}

    /**
     * The result of a walk. {@code roots} are the depth-0 receipts; {@code lineageRoots} the nodes
     * that have no parent anywhere in the store (the origin of the lineage — a wallet snapshot, a
     * human idea); {@code truncated} says the depth cap stopped the walk short of the real graph.
     */
    public record Graph(
            List<String> roots,
            Direction direction,
            int maxDepth,
            boolean truncated,
            List<String> lineageRoots,
            List<Node> nodes,
            List<ReceiptEdge> edges,
            Summary summary) {}

    /** A direct parent or child, with the role of the edge that links it. */
    public record Neighbour(ReceiptEdge.Role role, Node node) {}

    private final LineageRepository lineage;
    private final ReceiptService receipts;
    private final CryptobotMetrics metrics;

    public LineageService(LineageRepository lineage, ReceiptService receipts) {
        this(lineage, receipts, CryptobotMetrics.noop());
    }

    @Autowired
    public LineageService(LineageRepository lineage, ReceiptService receipts, CryptobotMetrics metrics) {
        this.lineage = lineage;
        this.receipts = receipts;
        this.metrics = metrics;
    }

    /** The graph around one receipt the owner holds: 404 if it is not theirs. */
    public Mono<Graph> lineageOf(UUID ownerId, String receiptHash, Direction direction, int depth) {
        return receipts.require(ownerId, receiptHash)
                .flatMap(r -> graph(List.of(r.receiptHash()), r.body().tenantId(), direction, depth));
    }

    /**
     * The DAG behind a proposal: its own receipts at depth 0 (STRATEGY, RISK_DECISION, SIMULATION,
     * EXECUTION) and, walking {@code direction}, everything they came from — the snapshot, the
     * human idea, the market analysis and its risk decision. Empty graph when the proposal predates
     * the receipt layer. The caller has already checked the proposal belongs to the owner.
     */
    public Mono<Graph> lineageOfProposal(UUID proposalId, String tenantId, Direction direction, int depth) {
        return receipts.forProposal(proposalId).map(IntelligenceReceipt::receiptHash).collectList()
                .flatMap(roots -> graph(roots, tenantId, direction, depth));
    }

    public Flux<Neighbour> parents(UUID ownerId, String receiptHash) {
        return neighbours(ownerId, receiptHash, Direction.ANCESTORS, null);
    }

    public Flux<Neighbour> children(UUID ownerId, String receiptHash) {
        return neighbours(ownerId, receiptHash, Direction.DESCENDANTS, null);
    }

    /** Children that declared {@code REUSES} — who built on this artifact instead of recomputing it (Endgame §7, §9). */
    public Flux<Neighbour> reusedBy(UUID ownerId, String receiptHash) {
        return neighbours(ownerId, receiptHash, Direction.DESCENDANTS, ReceiptEdge.Role.REUSES);
    }

    private Flux<Neighbour> neighbours(UUID ownerId, String receiptHash, Direction direction, ReceiptEdge.Role only) {
        return receipts.require(ownerId, receiptHash).flatMapMany(r -> {
            String hash = r.receiptHash();
            Flux<ReceiptEdge> edges = direction == Direction.ANCESTORS ? receipts.parentsOf(hash) : receipts.childrenOf(hash);
            return edges.filter(e -> only == null || e.role() == only).collectList().flatMapMany(list -> {
                if (list.isEmpty()) {
                    return Flux.empty();
                }
                Map<String, ReceiptEdge.Role> roleOf = new java.util.LinkedHashMap<>();
                for (ReceiptEdge e : list) {
                    roleOf.put(direction == Direction.ANCESTORS ? e.parentHash() : e.childHash(), e.role());
                }
                // depth 0 ⇒ just those receipts, tenant-scoped, with their degrees; a neighbour of another tenant is not returned.
                return lineage.walk(roleOf.keySet(), r.body().tenantId(), direction, 0)
                        .map(reached -> new Neighbour(roleOf.get(reached.receipt().receiptHash()), Node.of(reached)));
            });
        });
    }

    Mono<Graph> graph(List<String> roots, String tenantId, Direction direction, int depth) {
        int maxDepth = Math.min(depth < 0 ? DEFAULT_DEPTH : depth, LineageRepository.MAX_DEPTH);
        if (roots.isEmpty()) {
            return Mono.just(new Graph(List.of(), direction, maxDepth, false, List.of(), List.of(), List.of(), summarise(List.of(), List.of())));
        }
        return lineage.walk(roots, tenantId, direction, maxDepth).map(Node::of).collectList().flatMap(nodes -> {
            Set<String> present = new LinkedHashSet<>();
            for (Node n : nodes) {
                present.add(n.receiptHash());
            }
            return lineage.edgesAmong(present).collectList().map(edges -> {
                boolean truncated = nodes.stream().anyMatch(n -> n.depth() == maxDepth && hasMoreBeyond(n, direction, edges, present));
                List<String> lineageRoots = nodes.stream().filter(n -> n.parentCount() == 0).map(Node::receiptHash).toList();
                List<String> rootsPresent = roots.stream().filter(present::contains).toList();
                Graph g = new Graph(rootsPresent, direction, maxDepth, truncated, lineageRoots, nodes, edges, summarise(nodes, edges));
                metrics.provenanceDepth(nodes.stream().mapToInt(Node::depth).max().orElse(0));
                return g;
            });
        });
    }

    /** A node on the last layer still has edges pointing further in the walk's direction that the graph does not contain. */
    private static boolean hasMoreBeyond(Node n, Direction direction, List<ReceiptEdge> edges, Set<String> present) {
        long inGraphParents = edges.stream().filter(e -> e.childHash().equals(n.receiptHash())).count();
        long inGraphChildren = edges.stream().filter(e -> e.parentHash().equals(n.receiptHash())).count();
        return switch (direction) {
            case ANCESTORS -> n.parentCount() > inGraphParents;
            case DESCENDANTS -> n.childCount() > inGraphChildren;
            case BOTH -> n.parentCount() > inGraphParents || n.childCount() > inGraphChildren;
        };
    }

    static Summary summarise(Collection<Node> nodes, Collection<ReceiptEdge> edges) {
        long units = 0;
        long in = 0;
        long out = 0;
        BigDecimal usd = null;
        Set<String> tables = new HashSet<>();
        int anchored = 0;
        Map<String, Integer> byLevel = new TreeMap<>();
        Map<String, Integer> byKind = new TreeMap<>();
        for (Node n : nodes) {
            if (n.compute() != null) {
                units += n.compute().units();
                in += n.compute().inputTokens() == null ? 0 : n.compute().inputTokens();
                out += n.compute().outputTokens() == null ? 0 : n.compute().outputTokens();
            }
            if (n.cost() != null && n.cost().usd() != null) {
                usd = (usd == null ? BigDecimal.ZERO : usd).add(new BigDecimal(n.cost().usd()));
                tables.add(n.cost().priceTableVersion() == null ? "" : n.cost().priceTableVersion());
            }
            if (n.anchor() != null) {
                anchored++;
            }
            byLevel.merge(n.level().name(), 1, Integer::sum);
            byKind.merge(n.kind().name(), 1, Integer::sum);
        }
        int reused = (int) edges.stream().filter(e -> e.role() == ReceiptEdge.Role.REUSES).count();
        // A total across two price tables would be a number nobody can reproduce: report it only when there is one.
        String costUsd = usd == null || tables.size() != 1 ? null : usd.stripTrailingZeros().toPlainString();
        String table = tables.size() == 1 ? tables.iterator().next() : null;
        return new Summary(nodes.size(), edges.size(), units, in, out, costUsd, table, anchored, reused, byLevel, byKind);
    }

    /** {@code ancestors} | {@code descendants} | {@code both}, case-insensitive; anything else is a 400. */
    public static Direction direction(String raw) {
        if (raw == null || raw.isBlank()) {
            return Direction.ANCESTORS;
        }
        try {
            return Direction.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new io.lifeengine.cryptobot.application.controlplane.ControlPlaneExceptions.InvalidRequest("INVALID_DIRECTION",
                    "direction must be ancestors, descendants or both");
        }
    }
}
