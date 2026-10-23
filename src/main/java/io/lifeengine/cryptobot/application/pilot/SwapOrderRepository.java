package io.lifeengine.cryptobot.application.pilot;

import java.time.Instant;
import java.util.UUID;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** V17 {@code swap_order} + {@code swap_order_audit}. Every state change is a conditional update: it happens once or not at all. */
public interface SwapOrderRepository {

    Mono<SwapOrder> insert(SwapOrder order);

    Mono<SwapOrder> find(UUID id);

    Mono<SwapOrder> findByIdempotencyKey(String key);

    /**
     * PENDING_APPROVAL → APPROVED, only if the order is still pending and {@code jti} was never used for an approval
     * (UNIQUE); the execution window restarts at {@code expiresAt}. Empty = it was not pending (or the jti was spent).
     */
    Mono<SwapOrder> approve(UUID id, UUID approver, Instant at, String jti, Instant expiresAt);

    /** {@code from} → {@code to}, only if the order is in {@code from}. Empty = it was not. */
    Mono<SwapOrder> transition(UUID id, SwapOrder.Status from, SwapOrder.Status to, String error);

    Mono<Void> audit(UUID orderId, String event, UUID actor, String previewHash, String detail);

    /** The audit trail of one order, oldest first: {@code event} values. */
    Flux<String> auditEvents(UUID orderId);
}
