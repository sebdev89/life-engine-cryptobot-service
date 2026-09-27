package io.lifeengine.cryptobot.application.receipt;

import io.lifeengine.cryptobot.core.receipts.DeterministicInference;
import io.lifeengine.cryptobot.core.receipts.ReceiptBody;
import io.lifeengine.cryptobot.core.receipts.ReceiptEdge;
import java.util.Map;

/**
 * What a step hands to {@link ReceiptService#issue}: the body (hash-free), the role of each parent
 * edge (parents not listed here are {@code DERIVES_FROM}), optionally how to register the output
 * as an artifact, and — for an L1 step — the {@link DeterministicInference} (input and output
 * trees) the store keeps so {@code verify} can re-execute it.
 */
public record ReceiptDraft(ReceiptBody body, Map<String, ReceiptEdge.Role> parentRoles, Artifact artifact, DeterministicInference inference) {

    public record Artifact(String type, String schema, String storageRef) {}

    public ReceiptDraft {
        parentRoles = parentRoles == null ? Map.of() : Map.copyOf(parentRoles);
    }

    public static ReceiptDraft of(ReceiptBody body) {
        return new ReceiptDraft(body, Map.of(), null, null);
    }

    public ReceiptDraft withRole(String parentHash, ReceiptEdge.Role role) {
        Map<String, ReceiptEdge.Role> roles = new java.util.HashMap<>(parentRoles);
        roles.put(parentHash, role);
        return new ReceiptDraft(body, roles, artifact, inference);
    }

    public ReceiptDraft withArtifact(String type, String schema, String storageRef) {
        return new ReceiptDraft(body, parentRoles, new Artifact(type, schema, storageRef), inference);
    }

    /** The inference's hashes must be the ones the body names: {@link ReceiptService#issue} checks it. */
    public ReceiptDraft withInference(DeterministicInference inference) {
        return new ReceiptDraft(body, parentRoles, artifact, inference);
    }
}
