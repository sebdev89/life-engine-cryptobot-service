package io.lifeengine.cryptobot.domain.risk;

import java.util.Objects;

/**
 * {@code DeterministicDecision(modelId, modelVersion, weightsHash, canonicalInput) → canonicalOutput}
 * (Endgame §5): one execution of the deterministic engine, with everything a verifier needs to
 * run it again — the engine id and version (the code), the weights hash (the numbers), the
 * canonical input and the canonical output. {@link #inputHash()} and {@link #outputHash()} are what
 * the {@code RISK_DECISION} receipt carries; the input and output trees are what the store keeps
 * next to it so {@code verify} can re-execute.
 */
public record DeterministicDecision(String engineId, String engineVersion, String weightsHash, RiskInput input, RiskVerdict output) {

    public DeterministicDecision {
        Objects.requireNonNull(engineId, "engineId");
        Objects.requireNonNull(engineVersion, "engineVersion");
        Objects.requireNonNull(weightsHash, "weightsHash");
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(output, "output");
    }

    public String inputHash() {
        return input.hash();
    }

    public String outputHash() {
        return output.hash();
    }
}
