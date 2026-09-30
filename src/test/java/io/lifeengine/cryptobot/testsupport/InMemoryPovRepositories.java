package io.lifeengine.cryptobot.testsupport;

import io.lifeengine.cryptobot.proofofvalue.KnowledgeAssetRepository;
import io.lifeengine.cryptobot.proofofvalue.PovIdentity;
import io.lifeengine.cryptobot.proofofvalue.PovIdentityRepository;
import io.lifeengine.cryptobot.proofofvalue.PovKnowledgeAsset;
import io.lifeengine.cryptobot.proofofvalue.ValueEventRecord;
import io.lifeengine.cryptobot.proofofvalue.ValueEventRepository;
import java.util.Collection;
import java.util.Comparator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** In-memory Proof of Value stores (KAN-818, KAN-819) with the same contract as the R2DBC ones, incl. the identity join on read. */
public final class InMemoryPovRepositories {

    public static final Map<String, PovIdentity> IDENTITIES = new ConcurrentHashMap<>();
    public static final Map<UUID, ValueEventRecord> EVENTS = new ConcurrentHashMap<>();
    public static final Map<String, PovKnowledgeAsset> ASSETS = new ConcurrentHashMap<>();

    private InMemoryPovRepositories() {}

    public static void reset() {
        IDENTITIES.clear();
        EVENTS.clear();
        ASSETS.clear();
    }

    private static String key(String tenant, String id) {
        return tenant + "|" + id;
    }

    public static PovIdentityRepository identities() {
        return new PovIdentityRepository() {
            @Override
            public Mono<PovIdentity> insertIfAbsent(PovIdentity identity) {
                return Mono.fromSupplier(() -> IDENTITIES.putIfAbsent(key(identity.tenantId(), identity.id()), identity) == null ? identity : null);
            }

            @Override
            public Mono<PovIdentity> find(String tenantId, String id) {
                return Mono.justOrEmpty(IDENTITIES.get(key(tenantId, id)));
            }

            @Override
            public Mono<PovIdentity> setWalletIfMissing(String tenantId, String id, String wallet) {
                return Mono.fromSupplier(() -> {
                    PovIdentity[] updated = new PovIdentity[1];
                    IDENTITIES.computeIfPresent(key(tenantId, id), (k, i) -> {
                        if (i.wallet() != null) {
                            return i;
                        }
                        updated[0] = new PovIdentity(i.tenantId(), i.id(), i.kind(), i.displayName(), wallet, i.ownerId(), i.operatorId(), i.createdAt());
                        return updated[0];
                    });
                    return updated[0];
                });
            }

            @Override
            public Flux<PovIdentity> findAll(String tenantId) {
                return Flux.fromIterable(IDENTITIES.values()).filter(i -> i.tenantId().equals(tenantId))
                        .sort(Comparator.comparing(PovIdentity::createdAt).thenComparing(PovIdentity::id));
            }

            @Override
            public Flux<PovIdentity> findAll(String tenantId, Collection<String> ids) {
                return findAll(tenantId).filter(i -> ids.contains(i.id()));
            }
        };
    }

    public static ValueEventRepository events() {
        return new ValueEventRepository() {
            @Override
            public Mono<ValueEventRecord> insert(ValueEventRecord e) {
                return Mono.fromCallable(() -> {
                    boolean dup = EVENTS.values().stream().anyMatch(x -> x.tenantId().equals(e.tenantId())
                            && (x.valueEventHash().equals(e.valueEventHash()) || x.receiptHash().equals(e.receiptHash())));
                    if (dup) {
                        throw new IllegalStateException("duplicate value event " + e.valueEventHash());
                    }
                    for (ValueEventRecord.Contribution c : e.contributions()) {
                        if (!IDENTITIES.containsKey(key(e.tenantId(), c.identityId()))) {
                            throw new IllegalStateException("FK pov_contribution → pov_identity: " + c.identityId());
                        }
                    }
                    for (String a : e.knowledgeAssetIds()) {
                        if (!ASSETS.containsKey(key(e.tenantId(), a))) {
                            throw new IllegalStateException("FK pov_value_event_knowledge → pov_knowledge_asset: " + a);
                        }
                    }
                    for (ValueEventRecord.ComputeReceipt r : e.computeReceipts()) {
                        if (!IDENTITIES.containsKey(key(e.tenantId(), r.providerId()))) {
                            throw new IllegalStateException("FK pov_compute_receipt → pov_identity: " + r.providerId());
                        }
                    }
                    EVENTS.put(e.id(), e);
                    return e;
                }).flatMap(stored -> find(stored.tenantId(), stored.id()));
            }

            @Override
            public Mono<ValueEventRecord> find(String tenantId, UUID id) {
                return Mono.justOrEmpty(EVENTS.get(id)).filter(e -> e.tenantId().equals(tenantId)).map(InMemoryPovRepositories::joined);
            }

            @Override
            public Mono<ValueEventRecord> findByHash(String tenantId, String valueEventHash) {
                return Flux.fromIterable(EVENTS.values()).filter(e -> e.tenantId().equals(tenantId) && e.valueEventHash().equals(valueEventHash))
                        .next().map(InMemoryPovRepositories::joined);
            }

            @Override
            public Flux<ValueEventRecord> findRecent(String tenantId, int limit) {
                return Flux.fromIterable(EVENTS.values()).filter(e -> e.tenantId().equals(tenantId))
                        .sort(Comparator.comparing(ValueEventRecord::createdAt).reversed()).take(limit).map(InMemoryPovRepositories::joined);
            }

            @Override
            public Flux<ValueEventRecord> findAll(String tenantId) {
                return Flux.fromIterable(EVENTS.values()).filter(e -> e.tenantId().equals(tenantId))
                        .sort(Comparator.comparing(ValueEventRecord::acceptedAt).thenComparing(ValueEventRecord::createdAt)
                                .thenComparing(ValueEventRecord::id))
                        .map(InMemoryPovRepositories::joined);
            }
        };
    }

    public static KnowledgeAssetRepository assets() {
        return new KnowledgeAssetRepository() {
            @Override
            public Mono<PovKnowledgeAsset> insertIfAbsent(PovKnowledgeAsset a) {
                return Mono.fromCallable(() -> {
                    if (!IDENTITIES.containsKey(key(a.tenantId(), a.creatorId()))) {
                        throw new IllegalStateException("FK pov_knowledge_asset → pov_identity: " + a.creatorId());
                    }
                    return ASSETS.putIfAbsent(key(a.tenantId(), a.id()), a) == null;
                }).flatMap(inserted -> inserted ? find(a.tenantId(), a.id()) : Mono.empty());
            }

            @Override
            public Mono<PovKnowledgeAsset> find(String tenantId, String id) {
                return Mono.justOrEmpty(ASSETS.get(key(tenantId, id))).map(InMemoryPovRepositories::joined);
            }

            @Override
            public Flux<PovKnowledgeAsset> findAll(String tenantId) {
                return Flux.fromIterable(ASSETS.values()).filter(a -> a.tenantId().equals(tenantId))
                        .sort(Comparator.comparing(PovKnowledgeAsset::createdAt).thenComparing(PovKnowledgeAsset::id)).map(InMemoryPovRepositories::joined);
            }

            @Override
            public Flux<PovKnowledgeAsset> findAll(String tenantId, Collection<String> ids) {
                return findAll(tenantId).filter(a -> ids.contains(a.id()));
            }

            @Override
            public Flux<Usage> usage(String tenantId) {
                return events().findAll(tenantId).concatMapIterable(e -> e.knowledgeAssetIds().stream().map(a -> new Usage(a, e.id())).toList());
            }
        };
    }

    private static PovKnowledgeAsset joined(PovKnowledgeAsset a) {
        PovIdentity creator = IDENTITIES.get(key(a.tenantId(), a.creatorId()));
        return a.withCreatorDisplayName(creator == null ? null : creator.displayName());
    }

    private static ValueEventRecord joined(ValueEventRecord e) {
        return new ValueEventRecord(e.id(), e.tenantId(), e.ownerId(), e.receiptHash(), e.valueEventHash(), e.projectId(), e.taskId(), e.title(),
                e.artifactHash(), e.acceptanceHash(), e.acceptedAt(), e.distributionPolicy(), e.totalUnits(), e.canonical(), e.createdAt(),
                e.contributions().stream().map(c -> {
                    PovIdentity i = IDENTITIES.get(key(e.tenantId(), c.identityId()));
                    return new ValueEventRecord.Contribution(c.position(), c.identityId(), c.role(), c.units(), i.displayName(), i.kind());
                }).toList(),
                e.knowledgeAssetIds(),
                e.computeReceipts().stream().map(r -> new ValueEventRecord.ComputeReceipt(r.id(), r.position(), r.providerId(), r.providerWallet(), r.node(),
                        r.model(), r.inputTokens(), r.outputTokens(), r.gpuMillis(), r.estimatedCostMicroUsd(),
                        IDENTITIES.get(key(e.tenantId(), r.providerId())).displayName())).toList());
    }
}
