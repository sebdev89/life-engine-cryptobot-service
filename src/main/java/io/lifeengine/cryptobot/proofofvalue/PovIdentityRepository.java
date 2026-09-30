package io.lifeengine.cryptobot.proofofvalue;

import java.util.Collection;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Tenant-scoped store of contributors (KAN-818). */
public interface PovIdentityRepository {

    /** Inserts unless {@code (tenantId, id)} exists; empty when it already existed (the caller reads it back). */
    Mono<PovIdentity> insertIfAbsent(PovIdentity identity);

    Mono<PovIdentity> find(String tenantId, String id);

    Flux<PovIdentity> findAll(String tenantId);

    Flux<PovIdentity> findAll(String tenantId, Collection<String> ids);
}
