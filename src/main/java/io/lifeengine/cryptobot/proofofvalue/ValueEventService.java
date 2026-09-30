package io.lifeengine.cryptobot.proofofvalue;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.lifeengine.cryptobot.application.controlplane.ControlPlaneExceptions;
import io.lifeengine.cryptobot.application.controlplane.Receipts;
import io.lifeengine.cryptobot.application.receipt.AnchorService;
import io.lifeengine.cryptobot.application.receipt.ReceiptDraft;
import io.lifeengine.cryptobot.application.receipt.ReceiptService;
import io.lifeengine.cryptobot.core.receipts.Digests;
import io.lifeengine.cryptobot.core.receipts.IntelligenceReceipt;
import io.lifeengine.cryptobot.core.receipts.ReceiptBody;
import io.lifeengine.cryptobot.core.receipts.ReceiptInput;
import io.lifeengine.cryptobot.core.receipts.ReceiptKind;
import io.lifeengine.cryptobot.core.receipts.ReproducibilityLevel;
import io.lifeengine.cryptobot.observability.CryptobotMetrics;
import io.lifeengine.cryptobot.proofofvalue.ProofOfValueDtos.AcceptanceView;
import io.lifeengine.cryptobot.proofofvalue.ProofOfValueDtos.AnchorRef;
import io.lifeengine.cryptobot.proofofvalue.ProofOfValueDtos.ArtifactView;
import io.lifeengine.cryptobot.proofofvalue.ProofOfValueDtos.ComputeReceiptView;
import io.lifeengine.cryptobot.proofofvalue.ProofOfValueDtos.ContributionView;
import io.lifeengine.cryptobot.proofofvalue.ProofOfValueDtos.EventKnowledgeAssetView;
import io.lifeengine.cryptobot.proofofvalue.ProofOfValueDtos.ProofView;
import io.lifeengine.cryptobot.proofofvalue.ProofOfValueDtos.ValueEventRequest;
import io.lifeengine.cryptobot.proofofvalue.ProofOfValueDtos.ValueEventView;
import io.lifeengine.cryptobot.solana.rpc.SolanaRpcProperties;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Proof of Value V1: an ACCEPTED contribution becomes a {@code VALUE_EVENT} receipt.
 *
 * <pre>
 *   request ─▶ AcceptancePolicy V1 (5 stages true, else 422)
 *           ─▶ DistributionPolicy pov/equal-split/v1 (100 units)
 *           ─▶ canonical event (RFC 8785) ─▶ valueEventHash = sha256(canonical)
 *           ─▶ ReceiptService.issue(VALUE_EVENT, output.hash = valueEventHash)  ← signed, content-addressed
 *           ─▶ pov_value_event + pov_contribution (one transaction)
 *           ─▶ [anchor=true] AnchorService.sweep(wait=true): the same path as POST /anchors?wait=true
 * </pre>
 *
 * <p>Nothing here anchors on its own: the receipt enters the next Merkle batch of the existing
 * sweep, and the root goes in the {@code ir/1} memo on devnet. The signer does not change. The
 * anchor state of an event is read from its receipt on every read, so a RECORDED event reads as
 * ANCHORED as soon as a sweep finalizes its batch.
 *
 * <p>Idempotent by content: the same event posted again returns the stored one ({@code created=false}).
 *
 * <p>an internal ticket (V3/V4): {@code knowledgeAssets} must be registered (422 otherwise) and enter the canonical event expanded;
 * a creator of one of them who is not among the KNOWLEDGE_PROVIDER contributions gets one, added after the request's
 * contributions and before the split, with {@code derivedFrom} = those assets (provenance, not an economic decision:
 * the policy is still {@code pov/equal-split/v1}). {@code computeReceipts} name a registered provider with a wallet
 * (422 otherwise), which is denormalized into the receipt; they are committed in the canonical event and never enter
 * the distribution — compute cost is not economic value.
 */
@Service
public class ValueEventService {

    private static final Logger log = LoggerFactory.getLogger(ValueEventService.class);

    /** The {@code agentId} of the receipt: who issued it (this module), not who contributed. */
    public static final String RECEIPT_AGENT = "life-engine.proof-of-value";
    public static final String RECORDED = "RECORDED";
    public static final String ANCHORED = "ANCHORED";
    static final int DEFAULT_LIMIT = 20;
    static final int MAX_LIMIT = 100;

    public record Recorded(ValueEventView view, boolean created) {}

    private final ValueEventRepository events;
    private final PovIdentityRepository identities;
    private final KnowledgeAssetRepository assets;
    private final ReceiptService receipts;
    private final AnchorService anchors;
    private final SolanaRpcProperties rpc;
    private final CryptobotMetrics metrics;
    private final ObjectMapper json;
    private final Clock clock;
    /** the immediate reward of an event, shown on the event; {@code null} in unit tests that do not need it. */
    private final PayoutRepository payouts;
    /** the revenue shares of an event ("future participation"); {@code null} in unit tests that do not need it. */
    private final RevenueRepository revenues;

    @Autowired
    public ValueEventService(ValueEventRepository events, PovIdentityRepository identities, KnowledgeAssetRepository assets, ReceiptService receipts,
            AnchorService anchors, SolanaRpcProperties rpc, CryptobotMetrics metrics, ObjectMapper json, PayoutRepository payouts,
            RevenueRepository revenues) {
        this(events, identities, assets, receipts, anchors, rpc, metrics, json, Clock.systemUTC(), payouts, revenues);
    }

    ValueEventService(ValueEventRepository events, PovIdentityRepository identities, KnowledgeAssetRepository assets, ReceiptService receipts,
            AnchorService anchors, SolanaRpcProperties rpc, CryptobotMetrics metrics, ObjectMapper json, Clock clock) {
        this(events, identities, assets, receipts, anchors, rpc, metrics, json, clock, null);
    }

    ValueEventService(ValueEventRepository events, PovIdentityRepository identities, KnowledgeAssetRepository assets, ReceiptService receipts,
            AnchorService anchors, SolanaRpcProperties rpc, CryptobotMetrics metrics, ObjectMapper json, Clock clock, PayoutRepository payouts) {
        this(events, identities, assets, receipts, anchors, rpc, metrics, json, clock, payouts, null);
    }

    ValueEventService(ValueEventRepository events, PovIdentityRepository identities, KnowledgeAssetRepository assets, ReceiptService receipts,
            AnchorService anchors, SolanaRpcProperties rpc, CryptobotMetrics metrics, ObjectMapper json, Clock clock, PayoutRepository payouts,
            RevenueRepository revenues) {
        this.payouts = payouts;
        this.revenues = revenues;
        this.events = events;
        this.identities = identities;
        this.assets = assets;
        this.receipts = receipts;
        this.anchors = anchors;
        this.rpc = rpc;
        this.metrics = metrics;
        this.json = json;
        this.clock = clock;
    }

    // ---- write ---------------------------------------------------------------------------------

    public Mono<Recorded> record(UUID ownerUserId, ValueEventRequest req, boolean anchor) {
        List<String> violations = AcceptancePolicy.violations(req.acceptance().stages());
        if (!violations.isEmpty()) {
            metrics.povValueEvent("rejected");
            log.info("pov_value_event_rejected task={} violations={}", req.taskId(), violations);
            return Mono.error(new ProofOfValueExceptions.AcceptanceRejected(violations));
        }
        if (!DistributionPolicy.isSupported(req.distributionPolicy())) {
            return Mono.error(new ControlPlaneExceptions.InvalidRequest("UNSUPPORTED_DISTRIBUTION_POLICY",
                    "distributionPolicy must be " + DistributionPolicy.EQUAL_SPLIT_V1 + " (got " + req.distributionPolicy() + ")"));
        }
        Set<String> pairs = new HashSet<>();
        for (ProofOfValueDtos.ContributionRequest c : req.contributions()) {
            if (!pairs.add(c.identityId() + "|" + c.role())) {
                return Mono.error(new ControlPlaneExceptions.InvalidRequest("DUPLICATE_CONTRIBUTION",
                        "identity " + c.identityId() + " appears twice with role " + c.role()));
            }
        }
        List<String> assetIds = req.knowledgeAssets() == null ? List.of() : req.knowledgeAssets().stream().map(String::trim).toList();
        if (new HashSet<>(assetIds).size() != assetIds.size()) {
            return Mono.error(new ControlPlaneExceptions.InvalidRequest("DUPLICATE_KNOWLEDGE_ASSET", "a knowledge asset appears twice: " + assetIds));
        }
        List<ProofOfValueDtos.ComputeReceiptRequest> compute = req.computeReceipts() == null ? List.of() : req.computeReceipts();
        String tenant = Receipts.tenantOf(ownerUserId);
        return assets.findAll(tenant, assetIds).collectMap(PovKnowledgeAsset::id).flatMap(foundAssets -> {
            List<String> unknownAssets = assetIds.stream().filter(id -> !foundAssets.containsKey(id)).toList();
            if (!unknownAssets.isEmpty()) {
                return Mono.error(new ProofOfValueExceptions.Unprocessable("UNKNOWN_KNOWLEDGE_ASSET",
                        "knowledge assets must be registered first (POST /knowledge-assets)", unknownAssets));
            }
            List<PovKnowledgeAsset> used = assetIds.stream().map(foundAssets::get).toList();
            Set<String> ids = new LinkedHashSet<>();
            req.contributions().forEach(c -> ids.add(c.identityId()));
            compute.forEach(c -> ids.add(c.providerId()));
            return identities.findAll(tenant, ids).collectMap(PovIdentity::id).flatMap(found -> {
                List<String> missing = req.contributions().stream().map(ProofOfValueDtos.ContributionRequest::identityId).distinct()
                        .filter(id -> !found.containsKey(id)).toList();
                if (!missing.isEmpty()) {
                    return Mono.error(new ControlPlaneExceptions.InvalidRequest("UNKNOWN_IDENTITY", "not registered identities: " + missing));
                }
                List<String> unknownProviders = compute.stream().map(ProofOfValueDtos.ComputeReceiptRequest::providerId).distinct()
                        .filter(id -> !found.containsKey(id)).toList();
                if (!unknownProviders.isEmpty()) {
                    return Mono.error(new ProofOfValueExceptions.Unprocessable("UNKNOWN_COMPUTE_PROVIDER",
                            "compute providers must be registered identities", unknownProviders));
                }
                List<String> noWallet = compute.stream().map(ProofOfValueDtos.ComputeReceiptRequest::providerId).distinct()
                        .filter(id -> found.get(id).wallet() == null).toList();
                if (!noWallet.isEmpty()) {
                    return Mono.error(new ProofOfValueExceptions.Unprocessable("PROVIDER_WALLET_REQUIRED",
                            "a compute provider needs a wallet (register it with one)", noWallet));
                }
                return Mono.just(draft(tenant, req, used, found));
            });
        }).flatMap(d -> {
            return events.findByHash(tenant, d.valueEventHash())
                    .map(existing -> new Stored(existing, false))
                    .switchIfEmpty(Mono.defer(() -> issue(ownerUserId, tenant, d)));
        }).flatMap(stored -> (anchor ? anchorNow(ownerUserId, stored.record()) : Mono.just(stored.record()))
                .flatMap(r -> view(ownerUserId, r))
                .map(v -> {
                    if (anchor && ANCHORED.equals(v.status())) {
                        metrics.povValueEvent("anchored");
                    }
                    return new Recorded(v, stored.created());
                }));
    }

    private record Stored(ValueEventRecord record, boolean created) {}

    /** Everything that is computed before touching a store: units, trees, hashes. */
    record Draft(ValueEventRequest req, List<ValueEventCanonical.Contribution> contributions, List<ValueEventCanonical.KnowledgeRef> knowledge,
            List<ValueEventCanonical.Compute> compute, String schema, String distributionPolicy, String artifactHash, String acceptanceHash,
            String canonical, String valueEventHash) {}

    /** A request without knowledge assets nor compute receipts (V1 shape). */
    static Draft draft(String tenant, ValueEventRequest req) {
        return draft(tenant, req, List.of(), Map.of());
    }

    /**
     * {@code assets}: the request's knowledge assets, resolved, in request order. {@code identities}: at least every
     * compute provider (for its wallet). Pure: no store is touched.
     */
    static Draft draft(String tenant, ValueEventRequest req, List<PovKnowledgeAsset> assets, Map<String, PovIdentity> identities) {
        List<String[]> parts = new ArrayList<>();
        List<List<String>> derived = new ArrayList<>();
        Set<String> knowledgeProviders = new HashSet<>();
        for (ProofOfValueDtos.ContributionRequest c : req.contributions()) {
            parts.add(new String[] {c.identityId(), c.role().name()});
            derived.add(null);
            if (c.role() == ContributionRole.KNOWLEDGE_PROVIDER) {
                knowledgeProviders.add(c.identityId());
            }
        }
        // Provenance: an asset's creator not credited as KNOWLEDGE_PROVIDER gets that contribution, before the split.
        Map<String, List<String>> auto = new LinkedHashMap<>();
        for (PovKnowledgeAsset a : assets) {
            if (!knowledgeProviders.contains(a.creatorId())) {
                auto.computeIfAbsent(a.creatorId(), k -> new ArrayList<>()).add(a.id());
            }
        }
        auto.forEach((creator, ids) -> {
            parts.add(new String[] {creator, ContributionRole.KNOWLEDGE_PROVIDER.name()});
            derived.add(List.copyOf(ids));
        });
        int[] units = DistributionPolicy.equalSplit(parts.size());
        List<ValueEventCanonical.Contribution> cs = new ArrayList<>();
        for (int i = 0; i < units.length; i++) {
            cs.add(new ValueEventCanonical.Contribution(parts.get(i)[0], ContributionRole.valueOf(parts.get(i)[1]), units[i], derived.get(i)));
        }
        List<ValueEventCanonical.KnowledgeRef> knowledge = assets.stream()
                .map(a -> new ValueEventCanonical.KnowledgeRef(a.id(), a.version(), a.kind().name(), a.title(), a.creatorId(), a.contentHash())).toList();
        List<ValueEventCanonical.Compute> compute = new ArrayList<>();
        for (ProofOfValueDtos.ComputeReceiptRequest r : req.computeReceipts() == null ? List.<ProofOfValueDtos.ComputeReceiptRequest>of()
                : req.computeReceipts()) {
            PovIdentity provider = identities.get(r.providerId());
            if (provider == null || provider.wallet() == null) {
                throw new IllegalArgumentException("compute provider " + r.providerId() + " is not resolved with a wallet");
            }
            compute.add(new ValueEventCanonical.Compute(r.providerId(), provider.wallet(), r.node().trim(), r.model().trim(), r.inputTokens(),
                    r.outputTokens(), r.gpuSeconds().movePointRight(3).longValueExact(), r.estimatedCostMicroUsd()));
        }
        ValueEventCanonical.Artifact artifact = new ValueEventCanonical.Artifact(req.artifact().commitSha(), blank(req.artifact().prUrl()),
                blank(req.artifact().imageDigest()));
        ValueEventCanonical.Acceptance acceptance = new ValueEventCanonical.Acceptance(req.acceptance().source().trim(),
                req.acceptance().environment().trim(), req.acceptance().stages(), blank(req.acceptance().evidenceRef()), req.acceptance().acceptedAt());
        Map<String, Object> tree = ValueEventCanonical.eventTree(tenant, req.projectId(), req.taskId(), req.title().trim(), artifact, acceptance, cs,
                knowledge, compute, DistributionPolicy.EQUAL_SPLIT_V1, DistributionPolicy.TOTAL_UNITS);
        String canonical = ValueEventCanonical.canonical(tree);
        return new Draft(req, cs, knowledge, compute, (String) tree.get("schema"), DistributionPolicy.EQUAL_SPLIT_V1, (String) tree.get("artifactHash"),
                (String) tree.get("acceptanceHash"), canonical, Digests.sha256(canonical));
    }

    private Mono<Stored> issue(UUID ownerUserId, String tenant, Draft d) {
        ValueEventRequest req = d.req();
        Instant acceptedAt = req.acceptance().acceptedAt();
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("projectId", req.projectId());
        params.put("taskId", req.taskId());
        params.put("title", req.title().trim());
        params.put("distributionPolicy", d.distributionPolicy());
        params.put("totalUnits", DistributionPolicy.TOTAL_UNITS);
        params.put("contributions", d.contributions().size());
        params.put("acceptancePolicy", AcceptancePolicy.ID);
        if (!d.knowledge().isEmpty()) {
            params.put("knowledgeAssets", d.knowledge().size());
        }
        if (!d.compute().isEmpty()) {
            params.put("computeReceipts", d.compute().size());
        }
        // ir/1 keeps params flat: the full event is committed through output.hash = sha256(canonical), and
        // the two evidence hashes are declared as inputs so a verifier finds them without the event.
        ReceiptBody body = new ReceiptBody(null, ReceiptKind.VALUE_EVENT, tenant, ownerUserId.toString(), RECEIPT_AGENT, List.of(),
                List.of(new ReceiptInput(ReceiptInput.CONTRIBUTION_EVIDENCE, d.artifactHash()),
                        new ReceiptInput(ReceiptInput.ACCEPTANCE_EVIDENCE, d.acceptanceHash())),
                null, null, null, null, params, new ReceiptBody.Output(d.valueEventHash(), d.schema(), null),
                new ReceiptBody.Compute(null, null, 1, null), null, ReproducibilityLevel.L0_SIGNED, acceptedAt, acceptedAt,
                "pov:" + d.valueEventHash().substring(Digests.PREFIX.length()), null);
        return receipts.issue(ReceiptDraft.of(body)).flatMap(receipt -> {
            List<ValueEventRecord.Contribution> cs = new ArrayList<>();
            for (int i = 0; i < d.contributions().size(); i++) {
                ValueEventCanonical.Contribution c = d.contributions().get(i);
                cs.add(new ValueEventRecord.Contribution(i, c.identityId(), c.role(), c.units(), null, null));
            }
            List<ValueEventRecord.ComputeReceipt> rs = new ArrayList<>();
            for (int i = 0; i < d.compute().size(); i++) {
                ValueEventCanonical.Compute c = d.compute().get(i);
                rs.add(new ValueEventRecord.ComputeReceipt(UUID.randomUUID(), i, c.providerId(), c.providerWallet(), c.node(), c.model(), c.inputTokens(),
                        c.outputTokens(), c.gpuMillis(), c.estimatedCostMicroUsd(), null));
            }
            ValueEventRecord rec = new ValueEventRecord(UUID.randomUUID(), tenant, ownerUserId, receipt.receiptHash(), d.valueEventHash(),
                    req.projectId(), req.taskId(), req.title().trim(), d.artifactHash(), d.acceptanceHash(), acceptedAt, d.distributionPolicy(),
                    DistributionPolicy.TOTAL_UNITS, d.canonical(), clock.instant(), cs,
                    d.knowledge().stream().map(ValueEventCanonical.KnowledgeRef::id).toList(), rs);
            return events.insert(rec)
                    .map(stored -> new Stored(stored, true))
                    // A concurrent POST of the same content won the unique (tenant, value_event_hash): return its row.
                    .onErrorResume(ex -> events.findByHash(tenant, d.valueEventHash()).map(s -> new Stored(s, false))
                            .switchIfEmpty(Mono.error(ex)));
        }).doOnNext(s -> {
            if (s.created()) {
                metrics.povValueEvent("recorded");
                metrics.povComputeReceipts(s.record().computeReceipts().size());
                log.info("pov_value_event_recorded id={} receipt={} valueEventHash={} task={} contributions={} knowledgeAssets={} computeReceipts={}",
                        s.record().id(), s.record().receiptHash(), s.record().valueEventHash(), s.record().taskId(), s.record().contributions().size(),
                        s.record().knowledgeAssetIds().size(), s.record().computeReceipts().size());
            }
        });
    }

    /** {@code ?anchor=true}: the sweep of {@code POST /anchors?wait=true}, unless the receipt is already anchored. */
    private Mono<ValueEventRecord> anchorNow(UUID ownerUserId, ValueEventRecord rec) {
        return receipts.require(ownerUserId, rec.receiptHash()).flatMap(receipt -> {
            if (receipt.anchor() != null && receipt.anchor().tx() != null) {
                return Mono.just(rec);
            }
            return anchors.sweep(true)
                    .doOnNext(r -> log.info("pov_anchor_sweep id={} receipt={} batch={} status={} pending={}", rec.id(), rec.receiptHash(),
                            r.anchored() == null ? null : r.anchored().root(), r.anchored() == null ? null : r.anchored().status(), r.pending()))
                    .thenReturn(rec);
        });
    }

    // ---- read ----------------------------------------------------------------------------------

    public Mono<ValueEventView> get(UUID ownerUserId, UUID id) {
        return require(ownerUserId, id).flatMap(r -> view(ownerUserId, r));
    }

    public Flux<ValueEventView> list(UUID ownerUserId, Integer limit) {
        int l = limit == null ? DEFAULT_LIMIT : Math.max(1, Math.min(limit, MAX_LIMIT));
        return events.findRecent(Receipts.tenantOf(ownerUserId), l).concatMap(r -> view(ownerUserId, r));
    }

    /** The receipt's verification + Merkle inclusion, and the event's own hash recomputed from what is stored. */
    public Mono<ProofView> proof(UUID ownerUserId, UUID id) {
        return require(ownerUserId, id).flatMap(rec -> receipts.require(ownerUserId, rec.receiptHash()).flatMap(receipt -> Mono
                .zip(receipts.verify(receipt), anchors.inclusion(receipt))
                .map(t -> {
                    ReceiptService.Verification v = t.getT1();
                    AnchorService.Inclusion inc = t.getT2();
                    boolean eventOk = commitsTo(receipt, rec);
                    boolean verified = v.valid() && eventOk && inc.anchored() && Boolean.TRUE.equals(inc.proofValid());
                    return new ProofView(v, inc, rec.id(), rec.valueEventHash(), eventOk, inc.root(), inc.tx(), inc.slot(), explorerUrl(inc), verified);
                })));
    }

    /** The stored canonical still hashes to the stored id, and the signed receipt commits to that same hash. */
    static boolean commitsTo(IntelligenceReceipt receipt, ValueEventRecord rec) {
        return Digests.sha256(rec.canonical()).equals(rec.valueEventHash())
                && receipt.kind() == ReceiptKind.VALUE_EVENT
                && rec.valueEventHash().equals(receipt.body().output().hash());
    }

    private Mono<ValueEventRecord> require(UUID ownerUserId, UUID id) {
        return events.find(Receipts.tenantOf(ownerUserId), id)
                .switchIfEmpty(Mono.error(new ControlPlaneExceptions.NotFound("Value event " + id)));
    }

    Mono<ValueEventView> view(UUID ownerUserId, ValueEventRecord r) {
        Mono<ValueEventView> base = receipts.require(ownerUserId, r.receiptHash()).flatMap(anchors::inclusion).map(inc -> toView(r, inc));
        if (revenues != null) {
            // what each RevenueEvent linked to this one allocated its contributions.
            base = base.zipWith(revenues.sharesOf(r.tenantId(), r.id()).map(x -> new ProofOfValueDtos.RevenueShareView(x.revenueEventId(), x.lamports()))
                    .collectList(), ValueEventService::withRevenueShares);
        }
        if (payouts == null) {
            return base;
        }
        // the immediate reward, if any, at a glance.
        return base.zipWith(payouts.findByEvent(r.tenantId(), r.id()).map(Optional::of).defaultIfEmpty(Optional.empty()),
                (v, d) -> d.isEmpty() ? v : withDistribution(v, new ProofOfValueDtos.DistributionSummaryView(PovDistribution.statusOf(d.get().payouts()),
                        d.get().poolLamports(), d.get().confirmedLamports())));
    }

    private static ValueEventView withRevenueShares(ValueEventView v, List<ProofOfValueDtos.RevenueShareView> shares) {
        return new ValueEventView(v.id(), v.receiptHash(), v.valueEventHash(), v.artifactHash(), v.acceptanceHash(), v.status(), v.anchorStatus(), v.anchor(),
                v.distributionPolicy(), v.totalUnits(), v.contributions(), v.artifact(), v.acceptance(), v.knowledgeAssets(), v.computeReceipts(),
                v.projectId(), v.taskId(), v.title(), v.acceptedAt(), v.createdAt(), v.distribution(), shares);
    }

    private static ValueEventView withDistribution(ValueEventView v, ProofOfValueDtos.DistributionSummaryView d) {
        return new ValueEventView(v.id(), v.receiptHash(), v.valueEventHash(), v.artifactHash(), v.acceptanceHash(), v.status(), v.anchorStatus(), v.anchor(),
                v.distributionPolicy(), v.totalUnits(), v.contributions(), v.artifact(), v.acceptance(), v.knowledgeAssets(), v.computeReceipts(),
                v.projectId(), v.taskId(), v.title(), v.acceptedAt(), v.createdAt(), d, v.revenueShares());
    }

    @SuppressWarnings("unchecked")
    ValueEventView toView(ValueEventRecord r, AnchorService.Inclusion inc) {
        Map<String, Object> tree;
        try {
            tree = json.readValue(r.canonical(), Map.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("stored canonical of value event " + r.id() + " is not JSON", e);
        }
        Map<String, Object> a = (Map<String, Object>) tree.getOrDefault("artifact", Map.of());
        Map<String, Object> acc = (Map<String, Object>) tree.getOrDefault("acceptance", Map.of());
        Map<String, Boolean> stages = new LinkedHashMap<>();
        Map<String, Object> rawStages = (Map<String, Object>) acc.getOrDefault("stages", Map.of());
        for (String s : AcceptancePolicy.STAGES) {
            if (rawStages.get(s) instanceof Boolean b) {
                stages.put(s, b);
            }
        }
        ArtifactView artifact = new ArtifactView((String) a.get("commitSha"), (String) a.get("prUrl"), (String) a.get("imageDigest"));
        AcceptanceView acceptance = new AcceptanceView((String) acc.get("source"), (String) acc.get("environment"), stages,
                (String) acc.get("evidenceRef"), acc.get("acceptedAt") == null ? null : Instant.parse((String) acc.get("acceptedAt")));
        boolean anchored = inc != null && inc.anchored();
        AnchorRef anchor = anchored ? new AnchorRef(inc.root(), inc.tx(), inc.slot(), explorerUrl(inc)) : null;
        List<Object> rawContributions = (List<Object>) tree.getOrDefault("contributions", List.of());
        List<ContributionView> cs = r.contributions().stream()
                .map(c -> new ContributionView(c.identityId(), c.displayName(), c.kind(), c.role(), c.units(), derivedFrom(rawContributions, c.position())))
                .toList();
        List<EventKnowledgeAssetView> knowledge = new ArrayList<>();
        for (Object k : (List<Object>) tree.getOrDefault("knowledgeAssets", List.of())) {
            if (k instanceof Map<?, ?> m) {
                knowledge.add(new EventKnowledgeAssetView((String) m.get("id"), m.get("version") instanceof Number n ? n.intValue() : null,
                        (String) m.get("kind"), (String) m.get("title"), (String) m.get("creatorId"), (String) m.get("contentHash")));
            } else if (k != null) {
                // A V1 event could carry free-form references; they are shown as ids, nothing else is known about them.
                knowledge.add(new EventKnowledgeAssetView(k.toString(), null, null, null, null, null));
            }
        }
        List<ComputeReceiptView> compute = r.computeReceipts().stream()
                .map(c -> new ComputeReceiptView(c.id(), c.providerId(), c.providerDisplayName(), c.node(), c.model(), c.inputTokens(), c.outputTokens(),
                        c.gpuMillis() / 1000.0, c.estimatedCostMicroUsd(), c.providerWallet()))
                .toList();
        return new ValueEventView(r.id(), r.receiptHash(), r.valueEventHash(), r.artifactHash(), r.acceptanceHash(), anchored ? ANCHORED : RECORDED,
                inc == null ? null : inc.status(), anchor, r.distributionPolicy(), r.totalUnits(), cs, artifact, acceptance,
                knowledge, compute, r.projectId(), r.taskId(), r.title(), r.acceptedAt(), r.createdAt(), null, List.of());
    }

    @SuppressWarnings("unchecked")
    private static List<String> derivedFrom(List<Object> rawContributions, int position) {
        if (position < 0 || position >= rawContributions.size() || !(rawContributions.get(position) instanceof Map<?, ?> m)) {
            return null;
        }
        return m.get("derivedFrom") instanceof List<?> l ? (List<String>) l : null;
    }

    /**
     * The link a judge clicks. Devnet by default ({@code ?cluster=devnet}, from {@link AnchorService#explorerUrl}); when the
     * configured devnet RPC is a local validator (localhost or a compose service name) the explorer's custom-cluster link to
     * that RPC — scheme, host and port only, so a path or query (an API key) never ends up in a URL.
     */
    String explorerUrl(AnchorService.Inclusion inc) {
        if (inc == null || inc.tx() == null) {
            return null;
        }
        String local = localRpcOrigin();
        if (local != null) {
            return "https://explorer.solana.com/tx/" + inc.tx() + "?cluster=custom&customUrl=" + URLEncoder.encode(local, StandardCharsets.UTF_8);
        }
        return inc.explorerUrl() != null ? inc.explorerUrl() : AnchorService.explorerUrl("solana-devnet", inc.tx());
    }

    /** the same link for any devnet transaction (a payout): the custom-cluster link on a local validator, else devnet's. */
    String explorerTxUrl(String tx) {
        if (tx == null) {
            return null;
        }
        String local = localRpcOrigin();
        if (local != null) {
            return "https://explorer.solana.com/tx/" + tx + "?cluster=custom&customUrl=" + URLEncoder.encode(local, StandardCharsets.UTF_8);
        }
        return AnchorService.explorerUrl("solana-devnet", tx);
    }

    private String localRpcOrigin() {
        if (rpc == null || rpc.devnetUrl() == null) {
            return null;
        }
        try {
            URI u = URI.create(rpc.devnetUrl());
            String host = u.getHost();
            if (host == null || !(host.equals("localhost") || host.startsWith("127.") || !host.contains("."))) {
                return null;
            }
            return u.getScheme() + "://" + host + (u.getPort() > 0 ? ":" + u.getPort() : "");
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String blank(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }
}
