package io.lifeengine.cryptobot.proofofvalue;

import java.util.UUID;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Append-only store of ValueEvents (KAN-818): the event and its contributions are written in one transaction. */
public interface ValueEventRepository {

    Mono<ValueEventRecord> insert(ValueEventRecord event);

    Mono<ValueEventRecord> find(String tenantId, UUID id);

    /** The same content again: the idempotency key of {@code POST /value-events}. */
    Mono<ValueEventRecord> findByHash(String tenantId, String valueEventHash);

    /** Newest first. */
    Flux<ValueEventRecord> findRecent(String tenantId, int limit);
}
