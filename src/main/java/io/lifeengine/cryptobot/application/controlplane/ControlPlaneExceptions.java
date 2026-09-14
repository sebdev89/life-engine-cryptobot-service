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
