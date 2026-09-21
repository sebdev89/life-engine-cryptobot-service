package io.lifeengine.cryptobot.infrastructure.persistence.controlplane;

import io.lifeengine.cryptobot.domain.receipt.ReceiptEdge;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/**
 * {@link LineageRepository} over Postgres (KAN-393): one bounded {@code WITH RECURSIVE} per
 * direction. The recursion is anchored on the roots <em>filtered by tenant</em> and every step
 * joins the reached receipt on the same tenant, so a walk cannot cross a tenant boundary even if
 * an edge somehow did. {@code UNION} (not {@code UNION ALL}) plus the depth guard bounds the work
 * on any input; the DAG itself has no cycles (content-addressed ids), so the guard is a cap, not
 * a safety net.
 */
@Profile("!test")
@Component
public class LineageR2dbcStore implements LineageRepository {

    private static final String ANCESTORS =
            "WITH RECURSIVE walk (hash, depth) AS ("
                    + "  SELECT r.receipt_hash, 0 FROM intelligence_receipt r WHERE r.receipt_hash IN (:roots) AND r.tenant_id = :tenant"
                    + "  UNION"
                    + "  SELECT e.parent_hash, w.depth + 1 FROM walk w"
                    + "    JOIN receipt_edge e ON e.child_hash = w.hash"
                    + "    JOIN intelligence_receipt p ON p.receipt_hash = e.parent_hash AND p.tenant_id = :tenant"
                    + "   WHERE w.depth < :maxDepth"
                    + "), reached AS (SELECT hash, MIN(depth) AS depth FROM walk GROUP BY hash) ";

    private static final String DESCENDANTS =
            "WITH RECURSIVE walk (hash, depth) AS ("
                    + "  SELECT r.receipt_hash, 0 FROM intelligence_receipt r WHERE r.receipt_hash IN (:roots) AND r.tenant_id = :tenant"
                    + "  UNION"
                    + "  SELECT e.child_hash, w.depth + 1 FROM walk w"
                    + "    JOIN receipt_edge e ON e.parent_hash = w.hash"
                    + "    JOIN intelligence_receipt c ON c.receipt_hash = e.child_hash AND c.tenant_id = :tenant"
                    + "   WHERE w.depth < :maxDepth"
                    + "), reached AS (SELECT hash, MIN(depth) AS depth FROM walk GROUP BY hash) ";

    private static final String SELECT_REACHED =
            "SELECT " + ReceiptRows.COLS + ", x.depth AS depth,"
                    + " (SELECT count(*) FROM receipt_edge pe WHERE pe.child_hash = r.receipt_hash) AS parent_count,"
                    + " (SELECT count(*) FROM receipt_edge ce WHERE ce.parent_hash = r.receipt_hash) AS child_count"
                    + " FROM reached x JOIN intelligence_receipt r ON r.receipt_hash = x.hash"
                    + " ORDER BY x.depth, r.created_at, r.receipt_hash";

    private final DatabaseClient db;
    private final JsonDocs docs;

    public LineageR2dbcStore(DatabaseClient db, JsonDocs docs) {
        this.db = db;
        this.docs = docs;
    }

    @Override
    public Flux<Reached> walk(Collection<String> roots, String tenantId, Direction direction, int maxDepth) {
        if (roots.isEmpty()) {
            return Flux.empty();
        }
        int depth = Math.max(0, Math.min(maxDepth, MAX_DEPTH));
        List<String> rootList = List.copyOf(roots);
        return switch (direction) {
            case ANCESTORS -> query(ANCESTORS, rootList, tenantId, depth);
            case DESCENDANTS -> query(DESCENDANTS, rootList, tenantId, depth);
            case BOTH -> Flux.concat(query(ANCESTORS, rootList, tenantId, depth), query(DESCENDANTS, rootList, tenantId, depth))
                    .collectList()
                    .flatMapMany(all -> {
                        Map<String, Reached> byHash = new LinkedHashMap<>();
                        for (Reached r : all) {
                            byHash.merge(r.receipt().receiptHash(), r, (a, b) -> a.depth() <= b.depth() ? a : b);
                        }
                        List<Reached> merged = new ArrayList<>(byHash.values());
                        merged.sort(Comparator.comparingInt(Reached::depth)
                                .thenComparing(r -> r.receipt().createdAt())
                                .thenComparing(r -> r.receipt().receiptHash()));
                        return Flux.fromIterable(merged);
                    });
        };
    }

    private Flux<Reached> query(String cte, List<String> roots, String tenantId, int depth) {
        return db.sql(cte + SELECT_REACHED)
                .bind("roots", roots).bind("tenant", tenantId).bind("maxDepth", depth)
                .map((row, meta) -> new Reached(ReceiptRows.read(row, docs), row.get("depth", Integer.class),
                        row.get("parent_count", Long.class).intValue(), row.get("child_count", Long.class).intValue()))
                .all();
    }

    @Override
    public Flux<ReceiptEdge> edgesAmong(Collection<String> hashes) {
        if (hashes.isEmpty()) {
            return Flux.empty();
        }
        return db.sql("SELECT child_hash, parent_hash, role FROM receipt_edge WHERE child_hash IN (:hashes) AND parent_hash IN (:hashes)"
                        + " ORDER BY child_hash, parent_hash")
                .bind("hashes", List.copyOf(hashes))
                .map((row, meta) -> new ReceiptEdge(row.get("child_hash", String.class), row.get("parent_hash", String.class),
                        ReceiptEdge.Role.valueOf(row.get("role", String.class))))
                .all();
    }
}
