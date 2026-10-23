package io.lifeengine.cryptobot.application.pilot;

import java.time.Duration;
import java.util.UUID;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/**
 * The pilot's swap orders ({@code cryptobot.swaps.*}, KAN-1249). Off by default. Everything that decides who may
 * approve is here, in server configuration (gitops), never in a request.
 *
 * @param approverUserId the one user ({@code sub}) who may approve; unset = nobody can
 * @param approverTenant the approver's {@code tenant} claim; empty (default) = the claim must be ABSENT (a platform
 *     user). A present-but-empty tenant is not an absent one
 * @param approvalTtl how long an order may wait for approval, and an approved order for execution (default 2 min)
 * @param approvalIatMaxAge the approval token's {@code iat} may be at most this old (default 5 min)
 * @param cluster where orders are quoted, simulated and (later) sent: {@code devnet} (default) or {@code mainnet-beta}
 * @param createsPerHour new orders one user may propose per rolling hour (each one calls Jupiter and the RPC); default 20
 * @param confirmTimeout how long the executor waits for {@code confirmed} after the broadcast before it leaves the order
 *     UNKNOWN_SENT for reconciliation (default 30 s)
 * @param confirmPollInterval how often it asks meanwhile (default 1.5 s)
 * @param reconcileInterval how often {@link SwapReconcileJob} settles in-flight orders (default 1 min; only while enabled)
 */
@ConfigurationProperties(prefix = "cryptobot.swaps")
public record SwapsProperties(
        Boolean enabled,
        UUID approverUserId,
        String approverTenant,
        Duration approvalTtl,
        Duration approvalIatMaxAge,
        String cluster,
        Long maxLamportsIn,
        Long maxUsdcIn,
        Integer maxSlippageBps,
        Long maxPriorityFeeLamports,
        Integer createsPerHour,
        Duration confirmTimeout,
        Duration confirmPollInterval,
        Duration reconcileInterval) {

    /** The shape before execution (b2a). */
    public SwapsProperties(Boolean enabled, UUID approverUserId, String approverTenant, Duration approvalTtl, Duration approvalIatMaxAge, String cluster,
            Long maxLamportsIn, Long maxUsdcIn, Integer maxSlippageBps, Long maxPriorityFeeLamports, Integer createsPerHour) {
        this(enabled, approverUserId, approverTenant, approvalTtl, approvalIatMaxAge, cluster, maxLamportsIn, maxUsdcIn, maxSlippageBps,
                maxPriorityFeeLamports, createsPerHour, null, null, null);
    }

    /** The shape of b2b before the reconcile job. */
    public SwapsProperties(Boolean enabled, UUID approverUserId, String approverTenant, Duration approvalTtl, Duration approvalIatMaxAge, String cluster,
            Long maxLamportsIn, Long maxUsdcIn, Integer maxSlippageBps, Long maxPriorityFeeLamports, Integer createsPerHour, Duration confirmTimeout,
            Duration confirmPollInterval) {
        this(enabled, approverUserId, approverTenant, approvalTtl, approvalIatMaxAge, cluster, maxLamportsIn, maxUsdcIn, maxSlippageBps,
                maxPriorityFeeLamports, createsPerHour, confirmTimeout, confirmPollInterval, null);
    }

    @ConstructorBinding
    public SwapsProperties {
        enabled = enabled != null && enabled;
        approverTenant = approverTenant == null ? "" : approverTenant.trim();
        approvalTtl = approvalTtl == null ? Duration.ofMinutes(2) : approvalTtl;
        approvalIatMaxAge = approvalIatMaxAge == null ? Duration.ofMinutes(5) : approvalIatMaxAge;
        cluster = cluster == null || cluster.isBlank() ? "devnet" : cluster.trim();
        maxLamportsIn = maxLamportsIn == null || maxLamportsIn <= 0 ? 100_000_000L : maxLamportsIn;
        maxUsdcIn = maxUsdcIn == null || maxUsdcIn <= 0 ? 10_000_000L : maxUsdcIn;
        maxSlippageBps = maxSlippageBps == null || maxSlippageBps < 0 ? 100 : Math.min(maxSlippageBps, 10_000);
        maxPriorityFeeLamports = maxPriorityFeeLamports == null || maxPriorityFeeLamports <= 0 ? 200_000L : maxPriorityFeeLamports;
        createsPerHour = createsPerHour == null || createsPerHour <= 0 ? 20 : createsPerHour;
        confirmTimeout = confirmTimeout == null || confirmTimeout.isNegative() || confirmTimeout.isZero() ? Duration.ofSeconds(30) : confirmTimeout;
        confirmPollInterval = confirmPollInterval == null || confirmPollInterval.isNegative() || confirmPollInterval.isZero()
                ? Duration.ofMillis(1500) : confirmPollInterval;
        reconcileInterval = reconcileInterval == null || reconcileInterval.isNegative() || reconcileInterval.isZero()
                ? Duration.ofMinutes(1) : reconcileInterval;
        if (!cluster.equals("devnet") && !cluster.equals("mainnet-beta")) {
            throw new IllegalArgumentException("cryptobot.swaps.cluster must be devnet or mainnet-beta, was " + cluster);
        }
    }

    long maxIn(SwapOrder.Direction d) {
        return d == SwapOrder.Direction.SOL_USDC ? maxLamportsIn : maxUsdcIn;
    }
}
