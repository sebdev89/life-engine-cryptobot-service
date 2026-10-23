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

/** Same contract as {@code SwapOrderR2dbcStore} (V17), in memory: conditional transitions, a UNIQUE approval jti, a signature written once and the audit trail. */
public class InMemorySwapOrderRepository implements SwapOrderRepository {

    private final Map<UUID, SwapOrder> orders = new ConcurrentHashMap<>();
    private final Set<String> usedJtis = ConcurrentHashMap.newKeySet();
    private final Map<UUID, List<AuditRow>> audit = new ConcurrentHashMap<>();
    private final Map<UUID, Long> heights = new ConcurrentHashMap<>();

    /** One audit row as written. */
    public record AuditRow(String event, UUID actor, String previewHash, String detail) {}

    @Override
    public synchronized Mono<SwapOrder> insert(SwapOrder o) {
        if (orders.values().stream().anyMatch(e -> e.createdBy().equals(o.createdBy()) && e.idempotencyKey().equals(o.idempotencyKey()))) {
            return Mono.error(new org.springframework.dao.DataIntegrityViolationException("uq_swap_order_creator_key"));
        }
        orders.put(o.id(), o);
        return Mono.just(o);
    }

    @Override
    public Mono<SwapOrder> find(UUID id) {
        return Mono.justOrEmpty(orders.get(id));
    }

    @Override
    public Mono<SwapOrder> findByIdempotencyKey(UUID createdBy, String key) {
        return Mono.justOrEmpty(orders.values().stream().filter(o -> o.createdBy().equals(createdBy) && o.idempotencyKey().equals(key)).findFirst());
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
    public synchronized Mono<SwapOrder> markSigned(UUID id, String signature, long lastValidBlockHeight) {
        SwapOrder o = orders.get(id);
        if (o == null || o.status() != SwapOrder.Status.EXECUTING || o.txSignature() != null) {
            return Mono.empty();
        }
        SwapOrder s = new SwapOrder(o.id(), o.idempotencyKey(), o.createdBy(), o.status(), o.direction(), o.cluster(), o.owner(), o.inAmount(),
                o.minOut(), o.maxSlippageBps(), o.preview(), o.previewHash(), o.approvalNonce(), o.approvedBy(), o.approvedAt(), o.approvalJti(),
                o.expiresAt(), signature, o.error(), o.createdAt(), Instant.now());
        orders.put(id, s);
        heights.put(id, lastValidBlockHeight);
        return Mono.just(s);
    }

    @Override
    public Mono<Long> lastValidBlockHeight(UUID id) {
        return Mono.justOrEmpty(heights.get(id));
    }

    @Override
    public Flux<SwapOrder> inFlight() {
        Set<SwapOrder.Status> flying = Set.of(SwapOrder.Status.EXECUTING, SwapOrder.Status.SENT, SwapOrder.Status.UNKNOWN_SENT);
        return Flux.fromIterable(List.copyOf(orders.values())).filter(o -> flying.contains(o.status()));
    }

    /** Test hook: the height a signature was stored with, as an earlier run would have left it. */
    public void putHeight(UUID id, long lastValidBlockHeight) {
        heights.put(id, lastValidBlockHeight);
    }

    @Override
    public Flux<SwapOrder> spentSince(Instant since) {
        Set<SwapOrder.Status> spent = Set.of(SwapOrder.Status.EXECUTING, SwapOrder.Status.SENT, SwapOrder.Status.UNKNOWN_SENT, SwapOrder.Status.CONFIRMED);
        return Flux.fromIterable(List.copyOf(orders.values()))
                .filter(o -> spent.contains(o.status()) && o.approvedAt() != null && !o.approvedAt().isBefore(since));
    }

    /** Test hook: put an order in any state, as a crash or an earlier run would have left it. */
    public void put(SwapOrder o) {
        orders.put(o.id(), o);
    }

    @Override
    public Mono<Void> audit(UUID orderId, String event, UUID actor, String previewHash, String detail) {
        audit.computeIfAbsent(orderId, k -> new CopyOnWriteArrayList<>()).add(new AuditRow(event, actor, previewHash, detail));
        return Mono.empty();
    }

    @Override
    public Flux<String> auditEvents(UUID orderId) {
        return Flux.fromIterable(audit.getOrDefault(orderId, List.of())).map(AuditRow::event);
    }

    public List<AuditRow> auditRows(UUID orderId) {
        return List.copyOf(audit.getOrDefault(orderId, List.of()));
    }
}
