package io.lifeengine.cryptobot.proofofvalue;

import io.lifeengine.cryptobot.application.controlplane.ControlPlaneExceptions;
import io.lifeengine.cryptobot.application.controlplane.Receipts;
import io.lifeengine.cryptobot.observability.CryptobotMetrics;
import io.lifeengine.cryptobot.proofofvalue.ProofOfValueDtos.KnowledgeAssetRequest;
import io.lifeengine.cryptobot.proofofvalue.ProofOfValueDtos.KnowledgeAssetView;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Proof of Value V3 (KAN-820): the registry of knowledge assets — create (idempotent by id), list, get, each with
 * {@code usedIn}: the accepted ValueEvents that declared the asset. The creator must be a registered identity and the
 * parents registered assets of the same tenant (422 otherwise). The content itself never reaches the service: only its
 * {@code sha256}.
 */
@Service
public class KnowledgeAssetService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeAssetService.class);

    public record Created(KnowledgeAssetView asset, boolean created) {}

    private final KnowledgeAssetRepository assets;
    private final PovIdentityRepository identities;
    private final CryptobotMetrics metrics;
    private final Clock clock;

    @Autowired
    public KnowledgeAssetService(KnowledgeAssetRepository assets, PovIdentityRepository identities, CryptobotMetrics metrics) {
        this(assets, identities, metrics, Clock.systemUTC());
    }

    KnowledgeAssetService(KnowledgeAssetRepository assets, PovIdentityRepository identities, CryptobotMetrics metrics, Clock clock) {
        this.assets = assets;
        this.identities = identities;
        this.metrics = metrics;
        this.clock = clock;
    }

    public Mono<Created> create(UUID ownerUserId, KnowledgeAssetRequest req) {
        String tenant = Receipts.tenantOf(ownerUserId);
        String id = req.id().trim();
        List<String> parents = req.parentIds() == null ? List.of() : List.copyOf(new LinkedHashSet<>(req.parentIds().stream().map(String::trim).toList()));
        if (parents.contains(id)) {
            return Mono.error(new ControlPlaneExceptions.InvalidRequest("INVALID_PARENT", "an asset cannot be its own parent"));
        }
        return assets.find(tenant, id)
                .flatMap(existing -> view(tenant, existing).map(v -> new Created(v, false)))
                .switchIfEmpty(Mono.defer(() -> identities.find(tenant, req.creatorId())
                        .switchIfEmpty(Mono.error(new ProofOfValueExceptions.Unprocessable("UNKNOWN_IDENTITY",
                                "creatorId must be a registered identity", List.of(req.creatorId()))))
                        .then(assets.findAll(tenant, parents).map(PovKnowledgeAsset::id).collectList())
                        .flatMap(found -> {
                            List<String> missing = parents.stream().filter(p -> !found.contains(p)).toList();
                            if (!missing.isEmpty()) {
                                return Mono.error(new ProofOfValueExceptions.Unprocessable("UNKNOWN_KNOWLEDGE_ASSET",
                                        "parentIds must be registered knowledge assets", missing));
                            }
                            PovKnowledgeAsset asset = new PovKnowledgeAsset(tenant, id, req.version(), req.kind(), req.title().trim(), req.creatorId(),
                                    req.contentHash(), parents, clock.instant(), null);
                            return assets.insertIfAbsent(asset)
                                    .map(stored -> {
                                        metrics.povKnowledgeAsset();
                                        log.info("pov_knowledge_asset_created id={} version={} kind={} creator={}", stored.id(), stored.version(),
                                                stored.kind(), stored.creatorId());
                                        return new Created(KnowledgeAssetView.of(stored, List.of()), true);
                                    })
                                    // Lost a race with a concurrent create of the same id: the stored one wins.
                                    .switchIfEmpty(Mono.defer(() -> assets.find(tenant, id).flatMap(s -> view(tenant, s)).map(v -> new Created(v, false))));
                        })));
    }

    /** Oldest first, each with the events that used it. */
    public Flux<KnowledgeAssetView> list(UUID ownerUserId) {
        String tenant = Receipts.tenantOf(ownerUserId);
        return usage(tenant).flatMapMany(used -> assets.findAll(tenant).map(a -> KnowledgeAssetView.of(a, used.getOrDefault(a.id(), List.of()))));
    }

    public Mono<KnowledgeAssetView> get(UUID ownerUserId, String id) {
        String tenant = Receipts.tenantOf(ownerUserId);
        return assets.find(tenant, id)
                .switchIfEmpty(Mono.error(new ControlPlaneExceptions.NotFound("Knowledge asset " + id)))
                .flatMap(a -> view(tenant, a));
    }

    private Mono<KnowledgeAssetView> view(String tenant, PovKnowledgeAsset a) {
        return usage(tenant).map(used -> KnowledgeAssetView.of(a, used.getOrDefault(a.id(), List.of())));
    }

    private Mono<Map<String, List<UUID>>> usage(String tenant) {
        return assets.usage(tenant).collectList().map(links -> {
            Map<String, Set<UUID>> m = new LinkedHashMap<>();
            links.forEach(u -> m.computeIfAbsent(u.assetId(), k -> new LinkedHashSet<>()).add(u.valueEventId()));
            Map<String, List<UUID>> out = new LinkedHashMap<>();
            m.forEach((k, v) -> out.put(k, new ArrayList<>(v)));
            return out;
        });
    }
}
