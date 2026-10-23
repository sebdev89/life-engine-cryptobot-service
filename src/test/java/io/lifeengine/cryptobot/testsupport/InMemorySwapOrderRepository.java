package io.lifeengine.cryptobot.testsupport;

import io.lifeengine.cryptobot.application.pilot.SwapOrder;
import io.lifeengine.cryptobot.application.pilot.SwapOrderRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Same contract as {@code SwapOrderR2dbcStore} (V17), in memory: conditional transitions, a UNIQUE approval jti and the audit trail. */
public class InMemorySwapOrderRepository implements SwapOrderRepository {

    private final Map<UUID, SwapOrder> orders = new ConcurrentHashMap<>();
    private final Set<String> usedJtis = ConcurrentHashMap.newKeySet();
    private final Map<UUID, List<String>> audit = new ConcurrentHashMap<>();

    @Override
    public synchronized Mono<SwapOrder> insert(SwapOrder o) {
        if (orders.values().stream().anyMatch(e -> e.idempotencyKey().equals(o.idempotencyKey()))) {
            return Mono.error(new IllegalStateException("duplicate idempotency_key"));
        }
        orders.put(o.id(), o);
        return Mono.just(o);
    }

    @Override
    public Mono<SwapOrder> find(UUID id) {
        return Mono.justOrEmpty(orders.get(id));
    }

    @Override
    public Mono<SwapOrder> findByIdempotencyKey(String key) {
        return Mono.justOrEmpty(orders.values().stream().filter(o -> o.idempotencyKey().equals(key)).findFirst());
    }

    @Override
    public synchronized Mono<SwapOrder> approve(UUID id, UUID approver, Instant at, String jti, Instant expiresAt) {
        SwapOrder o = orders.get(id);
        if (o == null || o.status() != SwapOrder.Status.PENDING_APPROVAL || !usedJtis.add(jti)) {
            return Mono.empty();
        }
        SwapOrder a = new SwapOrder(o.id(), o.idempotencyKey(), o.createdBy(), SwapOrder.Status.APPROVED, o.direction(), o.cluster(), o.owner(),
                o.inAmount(), o.minOut(), o.maxSlippageBps(), o.preview(), o.previewHash(), o.approvalNonce(), approver, at, jti, expiresAt,
                o.txSignature(), o.error(), o.createdAt(), at);
        orders.put(id, a);
        return Mono.just(a);
    }

    @Override
    public synchronized Mono<SwapOrder> transition(UUID id, SwapOrder.Status from, SwapOrder.Status to, String error) {
        SwapOrder o = orders.get(id);
        if (o == null || o.status() != from) {
            return Mono.empty();
        }
        SwapOrder t = new SwapOrder(o.id(), o.idempotencyKey(), o.createdBy(), to, o.direction(), o.cluster(), o.owner(), o.inAmount(), o.minOut(),
                o.maxSlippageBps(), o.preview(), o.previewHash(), o.approvalNonce(), o.approvedBy(), o.approvedAt(), o.approvalJti(), o.expiresAt(),
                o.txSignature(), error == null ? o.error() : error, o.createdAt(), Instant.now());
        orders.put(id, t);
        return Mono.just(t);
    }

    @Override
    public Mono<Void> audit(UUID orderId, String event, UUID actor, String previewHash, String detail) {
        audit.computeIfAbsent(orderId, k -> new CopyOnWriteArrayList<>()).add(event);
        return Mono.empty();
    }

    @Override
    public Flux<String> auditEvents(UUID orderId) {
        return Flux.fromIterable(audit.getOrDefault(orderId, List.of()));
    }
}
