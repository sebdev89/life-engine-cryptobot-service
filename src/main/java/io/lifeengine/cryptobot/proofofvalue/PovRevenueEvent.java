package io.lifeengine.cryptobot.proofofvalue;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * (V7): an economic result attributed to ANCHORED ValueEvents ({@code pov_revenue_event} + {@code pov_revenue_link} +
 * its {@code pov_payout} rows, V15). {@code contributorPoolLamports + protocolFeeLamports + retainedLamports = amountLamports}
 * always: the pool is what the contributions were allocated (floor), the fee is only recorded, the rest is retained.
 * {@code receiptHash} is the REVENUE_EVENT receipt, {@code null} until every payout left PENDING.
 */
public record PovRevenueEvent(
        UUID id,
        String tenantId,
        String projectId,
        String sourceKind,
        String sourceRef,
        boolean simulated,
        long amountLamports,
        String policy,
        int revenueShareBps,
        int protocolFeeBps,
        long contributorPoolLamports,
        long protocolFeeLamports,
        long retainedLamports,
        String treasuryIdentityId,
        String receiptHash,
        String status,
        Instant createdAt,
        Instant updatedAt,
        List<Link> links,
        List<PovPayout> payouts) {

    public static final String PROPOSAL = "PROPOSAL";
    public static final String SIMULATED = "SIMULATED";
    public static final String EXTERNAL = "EXTERNAL";
    public static final List<String> SOURCE_KINDS = List.of(PROPOSAL, SIMULATED, EXTERNAL);

    public PovRevenueEvent {
        links = links == null ? List.of() : List.copyOf(links);
        payouts = payouts == null ? List.of() : List.copyOf(payouts);
    }

    /** A linked ValueEvent and the lamports its contributions were allocated. {@code title} comes from {@code pov_value_event} on read. */
    public record Link(UUID valueEventId, int position, long shareLamports, String title) {}

    public PovRevenueEvent withPayouts(List<PovPayout> ps) {
        return new PovRevenueEvent(id, tenantId, projectId, sourceKind, sourceRef, simulated, amountLamports, policy, revenueShareBps, protocolFeeBps,
                contributorPoolLamports, protocolFeeLamports, retainedLamports, treasuryIdentityId, receiptHash, status, createdAt, updatedAt, links, ps);
    }

    public PovRevenueEvent with(String newStatus, String newReceiptHash, Instant at) {
        return new PovRevenueEvent(id, tenantId, projectId, sourceKind, sourceRef, simulated, amountLamports, policy, revenueShareBps, protocolFeeBps,
                contributorPoolLamports, protocolFeeLamports, retainedLamports, treasuryIdentityId, newReceiptHash, newStatus, createdAt, at, links, payouts);
    }

    /** Lamports that reached a contributor (CONFIRMED on the chain). */
    public long confirmedLamports() {
        return payouts.stream().filter(p -> PovPayout.CONFIRMED.equals(p.status())).mapToLong(PovPayout::lamports).sum();
    }
}
