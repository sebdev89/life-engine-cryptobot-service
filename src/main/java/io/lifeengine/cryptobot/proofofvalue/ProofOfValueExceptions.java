package io.lifeengine.cryptobot.proofofvalue;

import java.util.List;

/** Proof of Value failures that are not the control plane's generic ones. */
public final class ProofOfValueExceptions {

    private ProofOfValueExceptions() {}

    /** AcceptancePolicy V1 refused the event: 422 with one detail per failing stage. */
    public static class AcceptanceRejected extends RuntimeException {
        private final List<String> violations;

        public AcceptanceRejected(List<String> violations) {
            super("AcceptancePolicy " + AcceptancePolicy.ID + " requires " + String.join(", ", AcceptancePolicy.STAGES) + " all true");
            this.violations = List.copyOf(violations);
        }

        public List<String> violations() {
            return violations;
        }
    }
}
