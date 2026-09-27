package io.lifeengine.cryptobot.core.receipts;

import io.lifeengine.cryptobot.core.intent.JsonCanonicalizer;
import java.util.Map;
import java.util.Objects;

/**
 * What the service keeps next to an L1 receipt so {@code verify} can <b>re-execute</b> it
 * (Endgame §10, L1): the engine that ran (id + version = the code, {@code weightsHash} = the
 * numbers), the canonical input tree and the canonical output tree. The receipt itself carries
 * only their hashes; this row carries the trees. Both hashes are recomputed from the trees at
 * verification time — a stored hash is never trusted on its own.
 *
 * <p>Nothing here is secret: the input is the quantised portfolio (mints, basis points, micro
 * dollars), the output the discrete verdict. It is still owner-scoped through the receipt.
 */
public record DeterministicInference(
        String receiptHash,
        String tenantId,
        String engineId,
        String engineVersion,
        String weightsHash,
        Map<String, Object> input,
        String inputHash,
        Map<String, Object> output,
        String outputHash) {

    public DeterministicInference {
        receiptHash = receiptHash == null ? null : Digests.requireHash("receiptHash", receiptHash);
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(engineId, "engineId");
        Objects.requireNonNull(engineVersion, "engineVersion");
        weightsHash = Digests.requireHash("weightsHash", weightsHash);
        input = Map.copyOf(Objects.requireNonNull(input, "input"));
        output = Map.copyOf(Objects.requireNonNull(output, "output"));
        inputHash = Digests.requireHash("inputHash", inputHash);
        outputHash = Digests.requireHash("outputHash", outputHash);
    }

    /** Before the receipt exists: the hash is bound by {@link #bound(String)} once the receipt is sealed. */
    public static DeterministicInference unbound(String tenantId, String engineId, String engineVersion, String weightsHash,
            Map<String, Object> input, Map<String, Object> output) {
        return new DeterministicInference(null, tenantId, engineId, engineVersion, weightsHash, input, hashOf(input), output, hashOf(output));
    }

    public DeterministicInference bound(String receiptHash) {
        return new DeterministicInference(receiptHash, tenantId, engineId, engineVersion, weightsHash, input, inputHash, output, outputHash);
    }

    public static String hashOf(Map<String, Object> tree) {
        return Digests.sha256(JsonCanonicalizer.canonicalBytes(tree));
    }
}
