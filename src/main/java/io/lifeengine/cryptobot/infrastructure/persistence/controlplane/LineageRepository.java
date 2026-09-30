package io.lifeengine.cryptobot.infrastructure.persistence.controlplane;

import io.lifeengine.cryptobot.core.receipts.IntelligenceReceipt;
import io.lifeengine.cryptobot.core.receipts.ReceiptEdge;
import java.util.Collection;
import reactor.core.publisher.Flux;

/**
 * Walks of the provenance DAG (Endgame §7): bounded {@code WITH RECURSIVE} over
 * {@code receipt_edge}, always inside one tenant. Reads only — the DAG is append-only and this
 * interface has no write path at all.
 *
 * <p>Tenancy: a walk never leaves {@code tenantId}. Today every edge is intra-tenant by
 * construction ({@code ReceiptService} refuses a parent of another tenant), so the filter is
 * defence in depth; when receipts gain a {@code PUBLIC} visibility (Endgame §7 "publicado", P1)
 * the walk will admit public parents here and nowhere else.
 */
public interface LineageRepository {

    /** Which way to walk from the roots: towards parents, towards children, or both. */
    enum Direction {
        ANCESTORS,
        DESCENDANTS,
        BOTH
    }

    /** One receipt reached by a walk, with the shortest distance from the roots and its total degree in the store. */
    record Reached(IntelligenceReceipt receipt, int depth, int parentCount, int childCount) {}

    /** Hard cap on {@code maxDepth}: a DAG of the pipeline is a handful deep; 64 is "no limit" in practice without an unbounded query. */
    int MAX_DEPTH = 64;

    /**
     * Every receipt of {@code tenantId} reachable from {@code roots} within {@code maxDepth} edges
     * in {@code direction}, roots included at depth 0. A receipt reachable by several paths is
     * returned once, at its minimum depth. Roots that do not exist or belong to another tenant are
     * simply not there. Order: depth, then {@code created_at}, then hash.
     */
    Flux<Reached> walk(Collection<String> roots, String tenantId, Direction direction, int maxDepth);

    /** The edges whose both ends are in {@code hashes} — the subgraph induced by a walk. */
    Flux<ReceiptEdge> edgesAmong(Collection<String> hashes);
}
