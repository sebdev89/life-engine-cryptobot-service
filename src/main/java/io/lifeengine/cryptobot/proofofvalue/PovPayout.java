package io.lifeengine.cryptobot.proofofvalue;

import java.time.Instant;
import java.util.UUID;

/**
 * KAN-822 (V5): what one contributing identity receives from a distribution — or, KAN-824 (V7), from a RevenueEvent: then
 * {@code revenueEventId} is set and {@code distributionId}/{@code valueEventId} are {@code null} (exactly one source, V15 CHECK). {@code wallet} is the identity's wallet
 * when the distribution was created ({@code null} ⇒ UNFUNDED: recorded, never paid). {@code displayName} comes from
 * {@code pov_identity} on read. {@code error} is the service's own text (signer/validator refusal, simulation, chain),
 * never a secret.
 */
public record PovPayout(
        UUID id,
        UUID distributionId,
        UUID valueEventId,
        String tenantId,
        int position,
        String identityId,
        String displayName,
        String wallet,
        long lamports,
        String status,
        String txSignature,
        String explorerUrl,
        String error,
        String policy,
        Instant createdAt,
        Instant updatedAt,
        UUID revenueEventId) {

    public static final String PENDING = "PENDING";
    public static final String SUBMITTED = "SUBMITTED";
    public static final String CONFIRMED = "CONFIRMED";
    public static final String FAILED = "FAILED";
    public static final String UNFUNDED = "UNFUNDED";

    /** A V5 payout (of a value event's distribution). */
    public PovPayout(UUID id, UUID distributionId, UUID valueEventId, String tenantId, int position, String identityId, String displayName, String wallet,
            long lamports, String status, String txSignature, String explorerUrl, String error, String policy, Instant createdAt, Instant updatedAt) {
        this(id, distributionId, valueEventId, tenantId, position, identityId, displayName, wallet, lamports, status, txSignature, explorerUrl, error, policy,
                createdAt, updatedAt, null);
    }

    /** KAN-824: a payout of a RevenueEvent. */
    public static PovPayout ofRevenue(UUID id, UUID revenueEventId, String tenantId, int position, String identityId, String wallet, long lamports,
            String status, String policy, Instant at) {
        return new PovPayout(id, null, null, tenantId, position, identityId, null, wallet, lamports, status, null, null, null, policy, at, at, revenueEventId);
    }

    public boolean isRevenue() {
        return revenueEventId != null;
    }

    public PovPayout with(String newStatus, String tx, String explorer, String err, Instant at) {
        return new PovPayout(id, distributionId, valueEventId, tenantId, position, identityId, displayName, wallet, lamports, newStatus, tx, explorer,
                err == null ? null : (err.length() > 500 ? err.substring(0, 500) : err), policy, createdAt, at, revenueEventId);
    }

    public PovPayout withDisplayName(String name) {
        return new PovPayout(id, distributionId, valueEventId, tenantId, position, identityId, name, wallet, lamports, status, txSignature, explorerUrl,
                error, policy, createdAt, updatedAt, revenueEventId);
    }
}
