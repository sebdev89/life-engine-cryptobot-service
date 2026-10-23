package io.lifeengine.cryptobot.application.pilot;

import java.time.Instant;
import java.util.UUID;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** V17 {@code swap_order} + {@code swap_order_audit}. Every state change is a conditional update: it happens once or not at all. */
public interface SwapOrderRepository {

    /** Fails with {@code DataIntegrityViolationException} if the creator already used that idempotency key. */
    Mono<SwapOrder> insert(SwapOrder order);

    Mono<SwapOrder> find(UUID id);

    /** Idempotency keys are per creator: one user cannot occupy another's. */
    Mono<SwapOrder> findByIdempotencyKey(UUID createdBy, String key);

    /**
     * PENDING_APPROVAL → APPROVED, only if the order is still pending and {@code jti} was never used for an approval
     * (UNIQUE); the execution window restarts at {@code expiresAt}. Empty = it was not pending (or the jti was spent).
     */
    Mono<SwapOrder> approve(UUID id, UUID approver, Instant at, String jti, Instant expiresAt);

    /** {@code from} → {@code to}, only if the order is in {@code from}. Empty = it was not. */
    Mono<SwapOrder> transition(UUID id, SwapOrder.Status from, SwapOrder.Status to, String error);

    /**
     * Records the transaction signature of an EXECUTING order, once ({@code tx_signature} still null). It is written
     * after the signer answered and before the broadcast: from then on a crash leaves an order that can be looked up
     * on chain. Empty = it was not EXECUTING or already had one.
     */
    Mono<SwapOrder> markSigned(UUID id, String signature);

    /** Orders that may have moved funds (EXECUTING, SENT, UNKNOWN_SENT, CONFIRMED) approved at or after {@code since}. */
    Flux<SwapOrder> spentSince(Instant since);

    Mono<Void> audit(UUID orderId, String event, UUID actor, String previewHash, String detail);

    /** The audit trail of one order, oldest first: {@code event} values. */
    Flux<String> auditEvents(UUID orderId);
}
