package io.lifeengine.cryptobot.domain.receipt;

/**
 * One edge of the provenance DAG: {@code child} was produced from {@code parent}. The child's body
 * already lists the parent hash (that is what makes cycles impossible); the edge row exists so
 * lineage queries can walk the graph in either direction with an index instead of a JSON scan.
 */
public record ReceiptEdge(String childHash, String parentHash, Role role) {

    public enum Role {
        /** The default: the child used the parent's output. */
        DERIVES_FROM,
        /** The child checked the parent (a risk decision over a strategy). */
        VALIDATES,
        /** The child put the parent on the chain. */
        EXECUTES,
        /** The child reused an earlier, still-valid artifact instead of recomputing it. */
        REUSES
    }

    public ReceiptEdge {
        childHash = Digests.requireHash("childHash", childHash);
        parentHash = Digests.requireHash("parentHash", parentHash);
        role = role == null ? Role.DERIVES_FROM : role;
        if (childHash.equals(parentHash)) {
            throw new IllegalArgumentException("a receipt cannot be its own parent");
        }
    }
}
