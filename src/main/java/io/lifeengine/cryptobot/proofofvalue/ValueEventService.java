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
import io.lifeengine.cryptobot.proofofvalue.ProofOfValueDtos.ContributionView;
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
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Proof of Value V1 (KAN-818): an ACCEPTED contribution becomes a {@code VALUE_EVENT} receipt.
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
    private final ReceiptService receipts;
    private final AnchorService anchors;
    private final SolanaRpcProperties rpc;
    private final CryptobotMetrics metrics;
    private final ObjectMapper json;
    private final Clock clock;

    @Autowired
    public ValueEventService(ValueEventRepository events, PovIdentityRepository identities, ReceiptService receipts, AnchorService anchors,
            SolanaRpcProperties rpc, CryptobotMetrics metrics, ObjectMapper json) {
        this(events, identities, receipts, anchors, rpc, metrics, json, Clock.systemUTC());
    }

    ValueEventService(ValueEventRepository events, PovIdentityRepository identities, ReceiptService receipts, AnchorService anchors,
            SolanaRpcProperties rpc, CryptobotMetrics metrics, ObjectMapper json, Clock clock) {
        this.events = events;
        this.identities = identities;
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
        String tenant = Receipts.tenantOf(ownerUserId);
        Set<String> ids = new LinkedHashSet<>();
        req.contributions().forEach(c -> ids.add(c.identityId()));
        return identities.findAll(tenant, ids).map(PovIdentity::id).collectList().flatMap(found -> {
            List<String> missing = ids.stream().filter(id -> !found.contains(id)).toList();
            if (!missing.isEmpty()) {
                return Mono.error(new ControlPlaneExceptions.InvalidRequest("UNKNOWN_IDENTITY", "not registered identities: " + missing));
            }
            Draft d = draft(tenant, req);
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
    record Draft(ValueEventRequest req, List<ValueEventCanonical.Contribution> contributions, String distributionPolicy, String artifactHash,
            String acceptanceHash, String canonical, String valueEventHash) {}

    static Draft draft(String tenant, ValueEventRequest req) {
        int[] units = DistributionPolicy.equalSplit(req.contributions().size());
        List<ValueEventCanonical.Contribution> cs = new ArrayList<>();
        for (int i = 0; i < units.length; i++) {
            ProofOfValueDtos.ContributionRequest c = req.contributions().get(i);
            cs.add(new ValueEventCanonical.Contribution(c.identityId(), c.role(), units[i]));
        }
        ValueEventCanonical.Artifact artifact = new ValueEventCanonical.Artifact(req.artifact().commitSha(), blank(req.artifact().prUrl()),
                blank(req.artifact().imageDigest()));
        ValueEventCanonical.Acceptance acceptance = new ValueEventCanonical.Acceptance(req.acceptance().source().trim(),
                req.acceptance().environment().trim(), req.acceptance().stages(), blank(req.acceptance().evidenceRef()), req.acceptance().acceptedAt());
        Map<String, Object> tree = ValueEventCanonical.eventTree(tenant, req.projectId(), req.taskId(), req.title().trim(), artifact, acceptance, cs,
                req.knowledgeAssets(), req.computeReceipts(), DistributionPolicy.EQUAL_SPLIT_V1, DistributionPolicy.TOTAL_UNITS);
        String canonical = ValueEventCanonical.canonical(tree);
        return new Draft(req, cs, DistributionPolicy.EQUAL_SPLIT_V1, (String) tree.get("artifactHash"), (String) tree.get("acceptanceHash"),
                canonical, Digests.sha256(canonical));
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
        // ir/1 keeps params flat: the full event is committed through output.hash = sha256(canonical), and
        // the two evidence hashes are declared as inputs so a verifier finds them without the event.
        ReceiptBody body = new ReceiptBody(null, ReceiptKind.VALUE_EVENT, tenant, ownerUserId.toString(), RECEIPT_AGENT, List.of(),
                List.of(new ReceiptInput(ReceiptInput.CONTRIBUTION_EVIDENCE, d.artifactHash()),
                        new ReceiptInput(ReceiptInput.ACCEPTANCE_EVIDENCE, d.acceptanceHash())),
                null, null, null, null, params, new ReceiptBody.Output(d.valueEventHash(), ValueEventCanonical.SCHEMA, null),
                new ReceiptBody.Compute(null, null, 1, null), null, ReproducibilityLevel.L0_SIGNED, acceptedAt, acceptedAt,
                "pov:" + d.valueEventHash().substring(Digests.PREFIX.length()), null);
        return receipts.issue(ReceiptDraft.of(body)).flatMap(receipt -> {
            List<ValueEventRecord.Contribution> cs = new ArrayList<>();
            for (int i = 0; i < d.contributions().size(); i++) {
                ValueEventCanonical.Contribution c = d.contributions().get(i);
                cs.add(new ValueEventRecord.Contribution(i, c.identityId(), c.role(), c.units(), null, null));
            }
            ValueEventRecord rec = new ValueEventRecord(UUID.randomUUID(), tenant, ownerUserId, receipt.receiptHash(), d.valueEventHash(),
                    req.projectId(), req.taskId(), req.title().trim(), d.artifactHash(), d.acceptanceHash(), acceptedAt, d.distributionPolicy(),
                    DistributionPolicy.TOTAL_UNITS, d.canonical(), clock.instant(), cs);
            return events.insert(rec)
                    .map(stored -> new Stored(stored, true))
                    // A concurrent POST of the same content won the unique (tenant, value_event_hash): return its row.
                    .onErrorResume(ex -> events.findByHash(tenant, d.valueEventHash()).map(s -> new Stored(s, false))
                            .switchIfEmpty(Mono.error(ex)));
        }).doOnNext(s -> {
            if (s.created()) {
                metrics.povValueEvent("recorded");
                log.info("pov_value_event_recorded id={} receipt={} valueEventHash={} task={} contributions={}", s.record().id(),
                        s.record().receiptHash(), s.record().valueEventHash(), s.record().taskId(), s.record().contributions().size());
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
        return receipts.require(ownerUserId, r.receiptHash()).flatMap(anchors::inclusion).map(inc -> toView(r, inc));
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
        List<ContributionView> cs = r.contributions().stream()
                .map(c -> new ContributionView(c.identityId(), c.displayName(), c.kind(), c.role(), c.units())).toList();
        return new ValueEventView(r.id(), r.receiptHash(), r.valueEventHash(), r.artifactHash(), r.acceptanceHash(), anchored ? ANCHORED : RECORDED,
                inc == null ? null : inc.status(), anchor, r.distributionPolicy(), r.totalUnits(), cs, artifact, acceptance,
                (List<String>) tree.getOrDefault("knowledgeAssets", List.of()), (List<String>) tree.getOrDefault("computeReceipts", List.of()),
                r.projectId(), r.taskId(), r.title(), r.acceptedAt(), r.createdAt());
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
