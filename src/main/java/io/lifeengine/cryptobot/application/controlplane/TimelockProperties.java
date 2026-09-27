package io.lifeengine.cryptobot.application.controlplane;

import io.lifeengine.cryptobot.core.policy.PolicyVerdict;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Paper §19: an authorized intent still waits before it executes, and the wait grows with the
 * risk ({@code Risk ↑ ⇒ ExecutionFriction ↑}). The lock starts at approval; during it a human can
 * cancel and nothing can be signed.
 *
 * @param autonomous the wait for verdicts in the ALLOW tier (default none: lower-risk actions may
 *     execute immediately)
 * @param escalated the wait for ESCALATE verdicts — second agent or human signature (default 30 min)
 * @param executionWindow how long after the lock ends the proposal stays executable; the
 *     proposal's expiry is pushed to at least {@code executableAt + executionWindow} at approval
 */
@ConfigurationProperties(prefix = "cryptobot.policy.timelock")
public record TimelockProperties(Duration autonomous, Duration escalated, Duration executionWindow) {

    public TimelockProperties {
        autonomous = autonomous == null || autonomous.isNegative() ? Duration.ZERO : autonomous;
        escalated = escalated == null || escalated.isNegative() ? Duration.ofMinutes(30) : escalated;
        executionWindow = executionWindow == null || executionWindow.isNegative() ? Duration.ofMinutes(30) : executionWindow;
    }

    /** The lock for a verdict; an absent verdict (pre-KAN-436 row) gets the longest lock — unknown ⇒ most friction. */
    public Duration forVerdict(PolicyVerdict verdict) {
        if (verdict == null || verdict.decision() != PolicyVerdict.Decision.ALLOW) {
            return escalated;
        }
        return autonomous;
    }
}
