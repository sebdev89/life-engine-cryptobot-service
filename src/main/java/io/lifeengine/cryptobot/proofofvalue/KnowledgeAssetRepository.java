package io.lifeengine.cryptobot.proofofvalue;

import java.util.Collection;
import java.util.UUID;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Tenant-scoped store of knowledge assets. Reads join the creator's display name. */
public interface KnowledgeAssetRepository {

    /** Inserts unless {@code (tenantId, id)} exists; empty when it already existed (the caller reads it back). */
    Mono<PovKnowledgeAsset> insertIfAbsent(PovKnowledgeAsset asset);

    Mono<PovKnowledgeAsset> find(String tenantId, String id);

    /** Oldest first. */
    Flux<PovKnowledgeAsset> findAll(String tenantId);

    Flux<PovKnowledgeAsset> findAll(String tenantId, Collection<String> ids);

    /** Every (asset, value event) link of the tenant, in the order the events were accepted. */
    Flux<Usage> usage(String tenantId);

    record Usage(String assetId, UUID valueEventId) {}
}
