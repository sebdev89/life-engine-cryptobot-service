package io.lifeengine.cryptobot.proofofvalue;

import java.time.Instant;
import java.util.UUID;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** (V5): distributions and their payouts. Everything is tenant-scoped; one distribution per value event. */
public interface PayoutRepository {

    /** The distribution and its payouts in one transaction; errors when the event already has one (unique value_event_id). */
    Mono<PovDistribution> insert(PovDistribution distribution);

    /** With its payouts (display names joined), in position order. */
    Mono<PovDistribution> findByEvent(String tenantId, UUID valueEventId);

    /** Status, tx, explorer link, error and updated_at of one payout. */
    Mono<Void> updatePayout(PovPayout payout);

    /** Status, receipt hash and updated_at of the distribution. */
    Mono<Void> updateDistribution(PovDistribution distribution);

    /** every payout of the tenant (distributions and revenue events), newest first — the Treasury read model folds over it. */
    Flux<PovPayout> findAll(String tenantId);

    /** Every payout of one identity (V5 and, an internal ticket, V7), newest first (the rewards of {@code GET /identities/{id}}). */
    Flux<PovPayout> findByIdentity(String tenantId, String identityId);

    /** Lamports of payouts (of any source) SUBMITTED or CONFIRMED since {@code since}: the daily exposure the policy sees. */
    Mono<Long> lamportsSince(String tenantId, Instant since);
}
