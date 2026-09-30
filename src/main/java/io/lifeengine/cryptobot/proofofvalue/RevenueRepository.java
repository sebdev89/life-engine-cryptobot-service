package io.lifeengine.cryptobot.proofofvalue;

import java.util.UUID;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** KAN-824 (V7): revenue events, their links and their payouts. Everything is tenant-scoped; one revenue event per source. */
public interface RevenueRepository {

    /** The event, its links and its payouts in one transaction; errors when the source was already recorded (unique source). */
    Mono<PovRevenueEvent> insert(PovRevenueEvent event);

    /** With its links (titles joined) and payouts (display names joined), in position order. */
    Mono<PovRevenueEvent> find(String tenantId, UUID id);

    /** The idempotency key of {@code POST /revenue-events}. */
    Mono<PovRevenueEvent> findBySource(String tenantId, String sourceKind, String sourceRef);

    /** Newest first. */
    Flux<PovRevenueEvent> findRecent(String tenantId, int limit);

    /** KAN-825: every revenue event earned by one treasury identity, newest first (with links and payouts). */
    Flux<PovRevenueEvent> findByTreasury(String tenantId, String identityId);

    /** Status, receipt hash and updated_at of the event. */
    Mono<Void> updateStatus(PovRevenueEvent event);

    /** What one ValueEvent's contributions were allocated by each revenue event it is linked to, oldest first. */
    Flux<Share> sharesOf(String tenantId, UUID valueEventId);

    record Share(UUID revenueEventId, long lamports) {}
}
