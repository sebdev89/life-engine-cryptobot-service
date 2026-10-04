package io.lifeengine.cryptobot.proofofvalue;

import java.util.UUID;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Append-only store of ValueEvents: the event, its contributions and its knowledge links and compute
 * receipts are written in one transaction.
 */
public interface ValueEventRepository {

    Mono<ValueEventRecord> insert(ValueEventRecord event);

    Mono<ValueEventRecord> find(String tenantId, UUID id);

    /** The same content again: the idempotency key of {@code POST /value-events}. */
    Mono<ValueEventRecord> findByHash(String tenantId, String valueEventHash);

    /** Newest first. */
    Flux<ValueEventRecord> findRecent(String tenantId, int limit);

    /** Every event of the tenant, in the order they were accepted (the read models of V2 and V6 fold over it). */
    Flux<ValueEventRecord> findAll(String tenantId);
}
