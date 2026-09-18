package io.lifeengine.cryptobot.domain.receipt;

/**
 * The output of a receipt as a thing of its own: content-addressed, typed, and pointing at where
 * the service keeps the bytes ({@code storageRef}, e.g. {@code portfolio_snapshot:<id>}). The
 * bytes themselves stay inside the service; the artifact row is what a later receipt can declare
 * it {@code REUSES}.
 */
public record ReceiptArtifact(String artifactHash, String receiptHash, String type, String schema, String storageRef, String tenantId) {

    public ReceiptArtifact {
        artifactHash = Digests.requireHash("artifactHash", artifactHash);
        receiptHash = Digests.requireHash("receiptHash", receiptHash);
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("artifact type is required");
        }
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("artifact tenantId is required");
        }
    }
}
