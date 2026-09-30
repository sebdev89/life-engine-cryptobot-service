package io.lifeengine.cryptobot.testsupport;

import io.lifeengine.cryptobot.proofofvalue.PovIdentity;
import io.lifeengine.cryptobot.proofofvalue.PovIdentityRepository;
import io.lifeengine.cryptobot.proofofvalue.ValueEventRecord;
import io.lifeengine.cryptobot.proofofvalue.ValueEventRepository;
import java.util.Collection;
import java.util.Comparator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** In-memory Proof of Value stores (KAN-818) with the same contract as the R2DBC ones, incl. the identity join on read. */
public final class InMemoryPovRepositories {

    public static final Map<String, PovIdentity> IDENTITIES = new ConcurrentHashMap<>();
    public static final Map<UUID, ValueEventRecord> EVENTS = new ConcurrentHashMap<>();

    private InMemoryPovRepositories() {}

    public static void reset() {
        IDENTITIES.clear();
        EVENTS.clear();
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
        };
    }

    private static ValueEventRecord joined(ValueEventRecord e) {
        return new ValueEventRecord(e.id(), e.tenantId(), e.ownerId(), e.receiptHash(), e.valueEventHash(), e.projectId(), e.taskId(), e.title(),
                e.artifactHash(), e.acceptanceHash(), e.acceptedAt(), e.distributionPolicy(), e.totalUnits(), e.canonical(), e.createdAt(),
                e.contributions().stream().map(c -> {
                    PovIdentity i = IDENTITIES.get(key(e.tenantId(), c.identityId()));
                    return new ValueEventRecord.Contribution(c.position(), c.identityId(), c.role(), c.units(), i.displayName(), i.kind());
                }).toList());
    }
}
