package io.lifeengine.cryptobot.application.receipt;

import io.lifeengine.cryptobot.domain.receipt.ReceiptBody;
import io.lifeengine.cryptobot.domain.receipt.ReceiptEdge;
import java.util.Map;

/**
 * What a step hands to {@link ReceiptService#issue}: the body (hash-free), the role of each parent
 * edge (parents not listed here are {@code DERIVES_FROM}) and, optionally, how to register the
 * output as an artifact.
 */
public record ReceiptDraft(ReceiptBody body, Map<String, ReceiptEdge.Role> parentRoles, Artifact artifact) {

    public record Artifact(String type, String schema, String storageRef) {}

    public ReceiptDraft {
        parentRoles = parentRoles == null ? Map.of() : Map.copyOf(parentRoles);
    }

    public static ReceiptDraft of(ReceiptBody body) {
        return new ReceiptDraft(body, Map.of(), null);
    }

    public ReceiptDraft withRole(String parentHash, ReceiptEdge.Role role) {
        Map<String, ReceiptEdge.Role> roles = new java.util.HashMap<>(parentRoles);
        roles.put(parentHash, role);
        return new ReceiptDraft(body, roles, artifact);
    }

    public ReceiptDraft withArtifact(String type, String schema, String storageRef) {
        return new ReceiptDraft(body, parentRoles, new Artifact(type, schema, storageRef));
    }
}
