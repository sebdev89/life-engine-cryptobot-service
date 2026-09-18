package io.lifeengine.cryptobot.application.receipt;

import io.lifeengine.cryptobot.adapters.solana.SolanaCluster;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code cryptobot.anchor.*} (KAN-394, Endgame §11).
 *
 * <ul>
 *   <li>{@code enabled} — the background job (startup + every {@code interval}). The service
 *       method stays callable through {@code POST /api/cryptobot/anchors} either way.
 *   <li>{@code cluster} — <b>devnet only, by policy</b>: any other value refuses to start. A
 *       mainnet anchor would spend real SOL and sign with a real key: that is a human gate, not a
 *       property.
 *   <li>{@code batch-size} — receipts per Merkle root; {@code max-attempts} — memo transactions
 *       tried for one root before it is ABANDONED and its receipts go to a new batch.
 *   <li>{@code finality-wait} / {@code poll-interval} — how long a synchronous
 *       {@code ?wait=true} call polls for {@code finalized} before handing over to the job.
 * </ul>
 */
@ConfigurationProperties(prefix = "cryptobot.anchor")
public record AnchorProperties(boolean enabled, String cluster, Duration interval, int batchSize, int maxAttempts, Duration finalityWait, Duration pollInterval) {

    public static final int MAX_BATCH = 1024;

    public AnchorProperties {
        cluster = cluster == null || cluster.isBlank() ? "devnet" : cluster.trim();
        if (SolanaCluster.parse(cluster) != SolanaCluster.DEVNET) {
            throw new IllegalArgumentException("cryptobot.anchor.cluster must be devnet: anchoring is devnet-only by policy (KAN-394), got " + cluster);
        }
        interval = interval == null ? Duration.ofSeconds(60) : interval;
        batchSize = batchSize <= 0 ? 256 : Math.min(batchSize, MAX_BATCH);
        maxAttempts = maxAttempts <= 0 ? 5 : maxAttempts;
        finalityWait = finalityWait == null ? Duration.ofSeconds(60) : finalityWait;
        pollInterval = pollInterval == null ? Duration.ofSeconds(2) : pollInterval;
    }

    public SolanaCluster solanaCluster() {
        return SolanaCluster.DEVNET;
    }

    /** The {@code chain} value written into the receipts' anchor. */
    public String chain() {
        return "solana-" + solanaCluster().id();
    }
}
