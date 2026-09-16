package io.lifeengine.cryptobot.application.reliability;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Knobs of the outbox publisher and the reconciliation job (KAN-403). Defaults are for a single
 * instance on the current host; none of them changes a financial decision, only how often the
 * system checks and how long it keeps trying before it asks a human.
 */
@ConfigurationProperties(prefix = "cryptobot.reliability")
public record ReliabilityProperties(Outbox outbox, Reconciliation reconciliation) {

    public ReliabilityProperties {
        outbox = outbox == null ? new Outbox(true, null, 0, 0, null, null) : outbox;
        reconciliation = reconciliation == null ? new Reconciliation(true, null, null, 0, 0) : reconciliation;
    }

    /**
     * @param enabled the background worker; {@code false} leaves events PENDING (they are still written)
     * @param pollInterval how often due events are looked for
     * @param batchSize events locked per tick
     * @param maxAttempts after this many failed deliveries the event is FAILED and dead-lettered
     * @param initialBackoff delay after the first failure; doubles every attempt
     * @param maxBackoff cap of the delay
     */
    public record Outbox(boolean enabled, Duration pollInterval, int batchSize, int maxAttempts, Duration initialBackoff, Duration maxBackoff) {
        public Outbox {
            pollInterval = pollInterval == null ? Duration.ofSeconds(2) : pollInterval;
            batchSize = batchSize <= 0 ? 50 : batchSize;
            maxAttempts = maxAttempts <= 0 ? 8 : maxAttempts;
            initialBackoff = initialBackoff == null ? Duration.ofSeconds(1) : initialBackoff;
            maxBackoff = maxBackoff == null ? Duration.ofMinutes(5) : maxBackoff;
        }
    }

    /**
     * @param enabled the background job (startup + periodic); the service method stays callable
     * @param interval period between sweeps
     * @param grace an in-flight row younger than this is assumed to be handled by a live request and is skipped
     * @param maxAttempts sweeps without a verdict before the trade is dead-lettered as ambiguous
     * @param batchSize rows per sweep
     */
    public record Reconciliation(boolean enabled, Duration interval, Duration grace, int maxAttempts, int batchSize) {
        public Reconciliation {
            interval = interval == null ? Duration.ofSeconds(30) : interval;
            grace = grace == null ? Duration.ofMinutes(2) : grace;
            maxAttempts = maxAttempts <= 0 ? 20 : maxAttempts;
            batchSize = batchSize <= 0 ? 100 : batchSize;
        }
    }
}
