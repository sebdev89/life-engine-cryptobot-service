package io.lifeengine.cryptobot.proofofvalue;

import java.util.Collection;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Tenant-scoped store of contributors. */
public interface PovIdentityRepository {

    /** Inserts unless {@code (tenantId, id)} exists; empty when it already existed (the caller reads it back). */
    Mono<PovIdentity> insertIfAbsent(PovIdentity identity);

    Mono<PovIdentity> find(String tenantId, String id);

    /**
     * sets the wallet of an identity that has none (a V1 agent registered before wallets were required).
     * Never overwrites a wallet; empty when the identity does not exist or already had one.
     */
    Mono<PovIdentity> setWalletIfMissing(String tenantId, String id, String wallet);

    Flux<PovIdentity> findAll(String tenantId);

    Flux<PovIdentity> findAll(String tenantId, Collection<String> ids);
}
