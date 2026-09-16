package io.lifeengine.cryptobot.application.controlplane;

import java.util.List;

/** Typed failures of the control plane; mapped to HTTP by {@code ControlPlaneExceptionHandler}. */
public final class ControlPlaneExceptions {

    private ControlPlaneExceptions() {}

    public static class NotFound extends RuntimeException {
        public NotFound(String what) {
            super(what + " not found");
        }
    }

    public static class InvalidRequest extends RuntimeException {
        private final String code;

        public InvalidRequest(String code, String message) {
            super(message);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    public static class Conflict extends RuntimeException {
        public Conflict(String message) {
            super(message);
        }
    }

    /**
     * The row moved under the caller (optimistic lock or status guard failed, KAN-403). Mapped to
     * 409: the caller re-reads; it never overwrites.
     */
    public static class StaleProposal extends Conflict {
        public StaleProposal(java.util.UUID proposalId, String expected) {
            super("Proposal " + proposalId + " changed concurrently (expected " + expected + ")");
        }
    }

    /** An {@code operationId} already used by another execution. */
    public static class DuplicateOperation extends Conflict {
        public DuplicateOperation(java.util.UUID operationId) {
            super("operationId " + operationId + " is already bound to another execution");
        }
    }

    public static class PolicyBlocked extends RuntimeException {
        private final List<String> violations;

        public PolicyBlocked(List<String> violations) {
            super("Blocked by policy: " + String.join("; ", violations));
            this.violations = List.copyOf(violations);
        }

        public List<String> violations() {
            return violations;
        }
    }

    public static class UpstreamUnavailable extends RuntimeException {
        public UpstreamUnavailable(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
