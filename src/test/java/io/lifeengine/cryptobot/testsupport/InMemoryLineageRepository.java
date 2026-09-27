package io.lifeengine.cryptobot.testsupport;

import io.lifeengine.cryptobot.core.receipts.IntelligenceReceipt;
import io.lifeengine.cryptobot.core.receipts.ReceiptEdge;
import io.lifeengine.cryptobot.infrastructure.persistence.controlplane.LineageRepository;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import reactor.core.publisher.Flux;

/**
 * {@link LineageRepository} over {@link InMemoryControlPlaneRepositories#RECEIPTS} and
 * {@link InMemoryControlPlaneRepositories#EDGES}: a breadth-first walk with the same contract as
 * the {@code WITH RECURSIVE} of the Postgres store (KAN-393) — tenant-scoped, depth-bounded,
 * minimum depth per receipt, roots at depth 0, ordered by depth / createdAt / hash.
 */
public final class InMemoryLineageRepository implements LineageRepository {

    private final Map<String, IntelligenceReceipt> receipts;
    private final List<ReceiptEdge> edges;

    public InMemoryLineageRepository() {
        this(InMemoryControlPlaneRepositories.RECEIPTS, InMemoryControlPlaneRepositories.EDGES);
    }

    public InMemoryLineageRepository(Map<String, IntelligenceReceipt> receipts, List<ReceiptEdge> edges) {
        this.receipts = receipts;
        this.edges = edges;
    }

    @Override
    public Flux<Reached> walk(Collection<String> roots, String tenantId, Direction direction, int maxDepth) {
        int cap = Math.max(0, Math.min(maxDepth, MAX_DEPTH));
        Map<String, Integer> depthOf = new LinkedHashMap<>();
        Deque<String> queue = new ArrayDeque<>();
        for (String root : roots) {
            IntelligenceReceipt r = receipts.get(root);
            if (r != null && r.body().tenantId().equals(tenantId) && !depthOf.containsKey(root)) {
                depthOf.put(root, 0);
                queue.add(root);
            }
        }
        List<ReceiptEdge> snapshot = new ArrayList<>(edges);
        while (!queue.isEmpty()) {
            String hash = queue.poll();
            int d = depthOf.get(hash);
            if (d >= cap) {
                continue;
            }
            Set<String> next = new HashSet<>();
            for (ReceiptEdge e : snapshot) {
                if ((direction == Direction.ANCESTORS || direction == Direction.BOTH) && e.childHash().equals(hash)) {
                    next.add(e.parentHash());
                }
                if ((direction == Direction.DESCENDANTS || direction == Direction.BOTH) && e.parentHash().equals(hash)) {
                    next.add(e.childHash());
                }
            }
            for (String n : next) {
                IntelligenceReceipt r = receipts.get(n);
                if (r != null && r.body().tenantId().equals(tenantId) && !depthOf.containsKey(n)) {
                    depthOf.put(n, d + 1);
                    queue.add(n);
                }
            }
        }
        List<Reached> out = new ArrayList<>();
        for (Map.Entry<String, Integer> e : depthOf.entrySet()) {
            IntelligenceReceipt r = receipts.get(e.getKey());
            int parents = (int) snapshot.stream().filter(x -> x.childHash().equals(e.getKey())).count();
            int children = (int) snapshot.stream().filter(x -> x.parentHash().equals(e.getKey())).count();
            out.add(new Reached(r, e.getValue(), parents, children));
        }
        out.sort(Comparator.comparingInt(Reached::depth).thenComparing(x -> x.receipt().createdAt()).thenComparing(x -> x.receipt().receiptHash()));
        return Flux.fromIterable(out);
    }

    @Override
    public Flux<ReceiptEdge> edgesAmong(Collection<String> hashes) {
        Set<String> in = new HashSet<>(hashes);
        return Flux.fromIterable(new ArrayList<>(edges))
                .filter(e -> in.contains(e.childHash()) && in.contains(e.parentHash()))
                .sort(Comparator.comparing(ReceiptEdge::childHash).thenComparing(ReceiptEdge::parentHash));
    }
}
