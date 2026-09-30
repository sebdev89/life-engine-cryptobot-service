package io.lifeengine.cryptobot.testsupport;

import io.lifeengine.cryptobot.proofofvalue.KnowledgeAssetRepository;
import io.lifeengine.cryptobot.proofofvalue.PayoutRepository;
import io.lifeengine.cryptobot.proofofvalue.PovDistribution;
import io.lifeengine.cryptobot.proofofvalue.PovPayout;
import io.lifeengine.cryptobot.proofofvalue.PovIdentity;
import io.lifeengine.cryptobot.proofofvalue.PovIdentityRepository;
import io.lifeengine.cryptobot.proofofvalue.PovKnowledgeAsset;
import io.lifeengine.cryptobot.proofofvalue.PovRevenueEvent;
import io.lifeengine.cryptobot.proofofvalue.RevenueRepository;
import io.lifeengine.cryptobot.proofofvalue.ValueEventRecord;
import io.lifeengine.cryptobot.proofofvalue.ValueEventRepository;
import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** In-memory Proof of Value stores with the same contract as the R2DBC ones, incl. the identity join on read. */
public final class InMemoryPovRepositories {

    public static final Map<String, PovIdentity> IDENTITIES = new ConcurrentHashMap<>();
    public static final Map<UUID, ValueEventRecord> EVENTS = new ConcurrentHashMap<>();
    public static final Map<String, PovKnowledgeAsset> ASSETS = new ConcurrentHashMap<>();
    /** distributions by id (payouts inside, the live rows by payout id). */
    public static final Map<UUID, PovDistribution> DISTRIBUTIONS = new ConcurrentHashMap<>();
    public static final Map<UUID, PovPayout> PAYOUTS = new ConcurrentHashMap<>();
    /** revenue events by id (links inside; their payouts live in PAYOUTS). */
    public static final Map<UUID, PovRevenueEvent> REVENUES = new ConcurrentHashMap<>();

    private InMemoryPovRepositories() {}

    public static void reset() {
        IDENTITIES.clear();
        EVENTS.clear();
        ASSETS.clear();
        DISTRIBUTIONS.clear();
        PAYOUTS.clear();
        REVENUES.clear();
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

    /** the same contract as PayoutR2dbcStore — unique per event, FK to identities, display name joined on read. */
    public static PayoutRepository payouts() {
        return new PayoutRepository() {
            @Override
            public Mono<PovDistribution> insert(PovDistribution d) {
                return Mono.fromCallable(() -> {
                    synchronized (DISTRIBUTIONS) {
                        if (DISTRIBUTIONS.values().stream().anyMatch(x -> x.valueEventId().equals(d.valueEventId()))) {
                            throw new IllegalStateException("duplicate key: uq_pov_distribution_event " + d.valueEventId());
                        }
                        for (PovPayout p : d.payouts()) {
                            if (!IDENTITIES.containsKey(key(d.tenantId(), p.identityId()))) {
                                throw new IllegalStateException("FK pov_payout → pov_identity: " + p.identityId());
                            }
                        }
                        DISTRIBUTIONS.put(d.id(), d.withPayouts(java.util.List.of()));
                        d.payouts().forEach(p -> PAYOUTS.put(p.id(), p));
                        return d;
                    }
                }).flatMap(x -> findByEvent(x.tenantId(), x.valueEventId()));
            }

            @Override
            public Mono<PovDistribution> findByEvent(String tenantId, UUID valueEventId) {
                return Flux.fromIterable(DISTRIBUTIONS.values()).filter(d -> d.tenantId().equals(tenantId) && d.valueEventId().equals(valueEventId)).next()
                        .map(d -> d.withPayouts(PAYOUTS.values().stream().filter(p -> d.id().equals(p.distributionId()))
                                .sorted(Comparator.comparingInt(PovPayout::position)).map(InMemoryPovRepositories::joined).toList()));
            }

            @Override
            public Mono<Void> updatePayout(PovPayout p) {
                return Mono.fromRunnable(() -> PAYOUTS.computeIfPresent(p.id(), (k, old) -> old.tenantId().equals(p.tenantId()) ? p : old));
            }

            @Override
            public Mono<Void> updateDistribution(PovDistribution d) {
                return Mono.fromRunnable(() -> DISTRIBUTIONS.computeIfPresent(d.id(), (k, old) -> old.tenantId().equals(d.tenantId())
                        ? old.with(d.status(), d.receiptHash(), d.updatedAt()) : old));
            }

            @Override
            public Flux<PovPayout> findByIdentity(String tenantId, String identityId) {
                return Flux.fromIterable(PAYOUTS.values()).filter(p -> p.tenantId().equals(tenantId) && p.identityId().equals(identityId))
                        .sort(Comparator.comparing(PovPayout::createdAt).reversed()).map(InMemoryPovRepositories::joined);
            }

            @Override
            public Flux<PovPayout> findAll(String tenantId) {
                return Flux.fromIterable(PAYOUTS.values()).filter(p -> p.tenantId().equals(tenantId))
                        .sort(Comparator.comparing(PovPayout::createdAt).reversed()).map(InMemoryPovRepositories::joined);
            }

            @Override
            public Mono<Long> lamportsSince(String tenantId, Instant since) {
                return Mono.fromSupplier(() -> PAYOUTS.values().stream().filter(p -> p.tenantId().equals(tenantId)
                                && (PovPayout.SUBMITTED.equals(p.status()) || PovPayout.CONFIRMED.equals(p.status())) && !p.updatedAt().isBefore(since))
                        .mapToLong(PovPayout::lamports).sum());
            }
        };
    }

    private static PovPayout joined(PovPayout p) {
        PovIdentity i = IDENTITIES.get(key(p.tenantId(), p.identityId()));
        return p.withDisplayName(i == null ? null : i.displayName());
    }

    /** the same contract as RevenueR2dbcStore — unique source, FKs to value events and identities, titles and names joined on read. */
    public static RevenueRepository revenues() {
        return new RevenueRepository() {
            @Override
            public Mono<PovRevenueEvent> insert(PovRevenueEvent e) {
                return Mono.fromCallable(() -> {
                    synchronized (REVENUES) {
                        if (REVENUES.values().stream().anyMatch(x -> x.tenantId().equals(e.tenantId()) && x.sourceKind().equals(e.sourceKind())
                                && x.sourceRef().equals(e.sourceRef()))) {
                            throw new IllegalStateException("duplicate key: uq_pov_revenue_source " + e.sourceKind() + " " + e.sourceRef());
                        }
                        if (e.contributorPoolLamports() + e.protocolFeeLamports() + e.retainedLamports() != e.amountLamports()) {
                            throw new IllegalStateException("CHECK chk_pov_revenue_split");
                        }
                        for (PovRevenueEvent.Link l : e.links()) {
                            ValueEventRecord v = EVENTS.get(l.valueEventId());
                            if (v == null || !v.tenantId().equals(e.tenantId())) {
                                throw new IllegalStateException("FK pov_revenue_link → pov_value_event: " + l.valueEventId());
                            }
                        }
                        for (PovPayout p : e.payouts()) {
                            if (!IDENTITIES.containsKey(key(e.tenantId(), p.identityId()))) {
                                throw new IllegalStateException("FK pov_payout → pov_identity: " + p.identityId());
                            }
                        }
                        REVENUES.put(e.id(), e.withPayouts(java.util.List.of()));
                        e.payouts().forEach(p -> PAYOUTS.put(p.id(), p));
                        return e;
                    }
                }).flatMap(x -> find(x.tenantId(), x.id()));
            }

            @Override
            public Mono<PovRevenueEvent> find(String tenantId, UUID id) {
                return Mono.justOrEmpty(REVENUES.get(id)).filter(e -> e.tenantId().equals(tenantId)).map(InMemoryPovRepositories::joined);
            }

            @Override
            public Mono<PovRevenueEvent> findBySource(String tenantId, String sourceKind, String sourceRef) {
                return Flux.fromIterable(REVENUES.values()).filter(e -> e.tenantId().equals(tenantId) && e.sourceKind().equals(sourceKind)
                        && e.sourceRef().equals(sourceRef)).next().map(InMemoryPovRepositories::joined);
            }

            @Override
            public Flux<PovRevenueEvent> findRecent(String tenantId, int limit) {
                return Flux.fromIterable(REVENUES.values()).filter(e -> e.tenantId().equals(tenantId))
                        .sort(Comparator.comparing(PovRevenueEvent::createdAt).reversed().thenComparing(PovRevenueEvent::id)).take(limit)
                        .map(InMemoryPovRepositories::joined);
            }

            @Override
            public Flux<PovRevenueEvent> findByTreasury(String tenantId, String identityId) {
                return findRecent(tenantId, Integer.MAX_VALUE).filter(e -> identityId.equals(e.treasuryIdentityId()));
            }

            @Override
            public Mono<Void> updateStatus(PovRevenueEvent e) {
                return Mono.fromRunnable(() -> REVENUES.computeIfPresent(e.id(), (k, old) -> old.tenantId().equals(e.tenantId())
                        ? old.with(e.status(), e.receiptHash(), e.updatedAt()) : old));
            }

            @Override
            public Flux<Share> sharesOf(String tenantId, UUID valueEventId) {
                return Flux.fromIterable(REVENUES.values()).filter(e -> e.tenantId().equals(tenantId))
                        .sort(Comparator.comparing(PovRevenueEvent::createdAt).thenComparing(PovRevenueEvent::id))
                        .flatMapIterable(e -> e.links().stream().filter(l -> l.valueEventId().equals(valueEventId))
                                .map(l -> new Share(e.id(), l.shareLamports())).toList());
            }
        };
    }

    private static PovRevenueEvent joined(PovRevenueEvent e) {
        return new PovRevenueEvent(e.id(), e.tenantId(), e.projectId(), e.sourceKind(), e.sourceRef(), e.simulated(), e.amountLamports(), e.policy(),
                e.revenueShareBps(), e.protocolFeeBps(), e.contributorPoolLamports(), e.protocolFeeLamports(), e.retainedLamports(), e.treasuryIdentityId(),
                e.receiptHash(), e.status(), e.createdAt(), e.updatedAt(),
                e.links().stream().map(l -> new PovRevenueEvent.Link(l.valueEventId(), l.position(), l.shareLamports(),
                        EVENTS.containsKey(l.valueEventId()) ? EVENTS.get(l.valueEventId()).title() : null)).toList(),
                PAYOUTS.values().stream().filter(p -> e.id().equals(p.revenueEventId())).sorted(Comparator.comparingInt(PovPayout::position))
                        .map(InMemoryPovRepositories::joined).toList());
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
