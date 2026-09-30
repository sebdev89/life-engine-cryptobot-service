package io.lifeengine.cryptobot.proofofvalue;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * KAN-822 (V5): the immediate reward of one ANCHORED ValueEvent ({@code pov_distribution} + its {@code pov_payout} rows).
 * {@code receiptHash} is the VALUE_DISTRIBUTION receipt, {@code null} until every payout left PENDING.
 */
public record PovDistribution(
        UUID id,
        String tenantId,
        UUID valueEventId,
        long poolLamports,
        String policy,
        String receiptHash,
        String status,
        Instant createdAt,
        Instant updatedAt,
        List<PovPayout> payouts) {

    public static final String IN_PROGRESS = "IN_PROGRESS";
    public static final String PARTIAL = "PARTIAL";
    public static final String COMPLETE = "COMPLETE";
    public static final String FAILED = "FAILED";

    public PovDistribution {
        payouts = payouts == null ? List.of() : List.copyOf(payouts);
    }

    public PovDistribution withPayouts(List<PovPayout> ps) {
        return new PovDistribution(id, tenantId, valueEventId, poolLamports, policy, receiptHash, status, createdAt, updatedAt, ps);
    }

    public PovDistribution with(String newStatus, String newReceiptHash, Instant at) {
        return new PovDistribution(id, tenantId, valueEventId, poolLamports, policy, newReceiptHash, newStatus, createdAt, at, payouts);
    }

    /** Lamports that reached a contributor (CONFIRMED on the chain). */
    public long confirmedLamports() {
        return payouts.stream().filter(p -> PovPayout.CONFIRMED.equals(p.status())).mapToLong(PovPayout::lamports).sum();
    }

    /**
     * Derived from the payouts, never guessed: any PENDING ⇒ IN_PROGRESS; every payout CONFIRMED ⇒ COMPLETE; none CONFIRMED
     * and none SUBMITTED ⇒ FAILED; otherwise (some paid, some failed, unfunded or still on their way) ⇒ PARTIAL.
     */
    public static String statusOf(List<PovPayout> payouts) {
        if (payouts.stream().anyMatch(p -> PovPayout.PENDING.equals(p.status()))) {
            return IN_PROGRESS;
        }
        if (!payouts.isEmpty() && payouts.stream().allMatch(p -> PovPayout.CONFIRMED.equals(p.status()))) {
            return COMPLETE;
        }
        if (payouts.stream().noneMatch(p -> PovPayout.CONFIRMED.equals(p.status()) || PovPayout.SUBMITTED.equals(p.status()))) {
            return FAILED;
        }
        return PARTIAL;
    }
}
