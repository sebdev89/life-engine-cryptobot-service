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

    /**
     * a well-formed request that breaks an attribution rule — an AGENT without wallet, a wallet that is not a
     * 32-byte public key, a knowledge asset or compute provider that is not registered. 422 with {@code code}.
     */
    public static class Unprocessable extends RuntimeException {
        private final String code;
        private final List<String> details;

        public Unprocessable(String code, String message, List<String> details) {
            super(message);
            this.code = code;
            this.details = details == null ? List.of() : List.copyOf(details);
        }

        public Unprocessable(String code, String message) {
            this(code, message, List.of());
        }

        public String code() {
            return code;
        }

        public List<String> details() {
            return details;
        }
    }
}
