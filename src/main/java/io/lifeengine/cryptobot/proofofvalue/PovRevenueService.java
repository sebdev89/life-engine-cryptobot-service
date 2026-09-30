package io.lifeengine.cryptobot.proofofvalue;

import io.lifeengine.cryptobot.application.controlplane.ControlPlaneExceptions;
import io.lifeengine.cryptobot.application.controlplane.Receipts;
import io.lifeengine.cryptobot.application.receipt.AnchorService;
import io.lifeengine.cryptobot.application.receipt.ReceiptDraft;
import io.lifeengine.cryptobot.application.receipt.ReceiptService;
import io.lifeengine.cryptobot.core.execution.ActionProposal;
import io.lifeengine.cryptobot.core.execution.ProposalStatus;
import io.lifeengine.cryptobot.core.receipts.Digests;
import io.lifeengine.cryptobot.core.receipts.ReceiptBody;
import io.lifeengine.cryptobot.core.receipts.ReceiptKind;
import io.lifeengine.cryptobot.core.receipts.ReproducibilityLevel;
import io.lifeengine.cryptobot.infrastructure.persistence.controlplane.ActionProposalRepository;
import io.lifeengine.cryptobot.observability.CryptobotMetrics;
import io.lifeengine.cryptobot.proofofvalue.ProofOfValueDtos.AnchorRef;
import io.lifeengine.cryptobot.proofofvalue.ProofOfValueDtos.LinkedValueEventView;
import io.lifeengine.cryptobot.proofofvalue.ProofOfValueDtos.RevenueEventRequest;
import io.lifeengine.cryptobot.proofofvalue.ProofOfValueDtos.RevenueEventView;
import io.lifeengine.cryptobot.proofofvalue.ProofOfValueDtos.RevenuePolicyView;
import io.lifeengine.cryptobot.proofofvalue.ProofOfValueDtos.RevenueSourceView;
import java.math.BigInteger;
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
 * Proof of Value V7 (KAN-824): a RevenueEvent — an economic result attributed to ANCHORED ValueEvents and split with the fixed
 * policy {@code pov/revenue-share/v1}.
 *
 * <pre>
 *   POST /revenue-events {projectId, source:{kind, ref}, amountLamports, linkedValueEventIds, simulated}
 *     ─▶ 409 unless the reward flow is enabled (the pool is paid with it) · 409 same source with other content (200 same content)
 *     ─▶ 422 source PROPOSAL whose proposal is not EXECUTED on the chain · 422 linked event unknown or not ANCHORED
 *     ─▶ pov/revenue-share/v1: fee = floor(amount × feeBps / 10 000) (recorded only); nominal pool = floor(amount × shareBps / 10 000)
 *          split among EVERY contribution of the linked events, floor(pool × units / Σunits); pool = Σ allocations; retained = the rest
 *     ─▶ per identity (its allocations added up) one payout; no wallet ⇒ UNFUNDED
 *     ─▶ pov_revenue_event + pov_revenue_link + pov_payout (one transaction; unique source ⇒ idempotent)
 *     ─▶ PovRewardService.payAll: the V5 flow — (I, S) ─▶ policy ─▶ validator ─▶ signer ─▶ confirm, one transfer per wallet
 *     ─▶ REVENUE_EVENT receipt (parents: the linked VALUE_EVENT receipts) ─▶ [anchor=true] AnchorService.sweep(wait=true)
 * </pre>
 *
 * <p>{@code simulated=true} (the demo) travels to every read, the receipt and the metrics: a simulated economic result is never
 * presented as real profit. Devnet SOL stands in for stablecoin settlement.
 */
@Service
public class PovRevenueService {

    private static final Logger log = LoggerFactory.getLogger(PovRevenueService.class);

    public static final String POLICY = "pov/revenue-share/v1";
    public static final String SCHEMA = "pov/revenue-event/v1";
    public static final String AGENT = "life-engine.proof-of-value.revenue";
    static final int DEFAULT_LIMIT = 20;
    static final int MAX_LIMIT = 100;
    static final long BPS = 10_000L;

    public record Recorded(RevenueEventView view, boolean created) {}

    /** One contribution's allocation. */
    record Allocation(UUID valueEventId, String identityId, ContributionRole role, int units, long lamports) {}

    /** One identity's payout before anything is stored ({@code wallet} null ⇒ UNFUNDED). */
    record Share(String identityId, String wallet, long units, long lamports) {}

    /** The whole split: {@code pool + fee + retained = amount}; {@code perEvent} = the allocations of each linked event, in link order. */
    record Split(long amount, long nominalPool, long pool, long fee, long retained, List<Allocation> allocations, Map<UUID, Long> perEvent) {}

    private final ValueEventRepository events;
    private final RevenueRepository revenues;
    private final PovIdentityRepository identities;
    private final ValueEventService valueEvents;
    private final PovRewardService rewards;
    private final ReceiptService receipts;
    private final AnchorService anchors;
    private final ActionProposalRepository proposals;
    private final PovRevenueProperties props;
    private final CryptobotMetrics metrics;
    private final Clock clock;

    @Autowired
    public PovRevenueService(ValueEventRepository events, RevenueRepository revenues, PovIdentityRepository identities, ValueEventService valueEvents,
            PovRewardService rewards, ReceiptService receipts, AnchorService anchors, ActionProposalRepository proposals, PovRevenueProperties props,
            CryptobotMetrics metrics) {
        this(events, revenues, identities, valueEvents, rewards, receipts, anchors, proposals, props, metrics, Clock.systemUTC());
    }

    PovRevenueService(ValueEventRepository events, RevenueRepository revenues, PovIdentityRepository identities, ValueEventService valueEvents,
            PovRewardService rewards, ReceiptService receipts, AnchorService anchors, ActionProposalRepository proposals, PovRevenueProperties props,
            CryptobotMetrics metrics, Clock clock) {
        this.events = events;
        this.revenues = revenues;
        this.identities = identities;
        this.valueEvents = valueEvents;
        this.rewards = rewards;
        this.receipts = receipts;
        this.anchors = anchors;
        this.proposals = proposals;
        this.props = props;
        this.metrics = metrics;
        this.clock = clock;
    }

    // ---- the split (pure) --------------------------------------------------------------------------

    /**
     * {@code pov/revenue-share/v1}. {@code linked} in request order; every contribution of every event takes
     * {@code floor(nominalPool × units / Σunits)}. The sum never exceeds the nominal pool; the floor's dust is retained.
     */
    static Split split(long amount, int shareBps, int feeBps, List<ValueEventRecord> linked) {
        long nominal = floorBps(amount, shareBps);
        long fee = floorBps(amount, feeBps);
        long totalUnits = linked.stream().flatMap(e -> e.contributions().stream()).mapToLong(ValueEventRecord.Contribution::units).sum();
        List<Allocation> allocations = new ArrayList<>();
        Map<UUID, Long> perEvent = new LinkedHashMap<>();
        long pool = 0;
        for (ValueEventRecord e : linked) {
            long eventShare = 0;
            for (ValueEventRecord.Contribution c : e.contributions()) {
                long lamports = totalUnits == 0 ? 0
                        : BigInteger.valueOf(nominal).multiply(BigInteger.valueOf(c.units())).divide(BigInteger.valueOf(totalUnits)).longValueExact();
                allocations.add(new Allocation(e.id(), c.identityId(), c.role(), c.units(), lamports));
                eventShare += lamports;
            }
            perEvent.merge(e.id(), eventShare, Long::sum);
            pool += eventShare;
        }
        return new Split(amount, nominal, pool, fee, amount - fee - pool, allocations, perEvent);
    }

    static long floorBps(long amount, int bps) {
        return BigInteger.valueOf(amount).multiply(BigInteger.valueOf(bps)).divide(BigInteger.valueOf(BPS)).longValueExact();
    }

    /** One payout per identity: its allocations (every role, every linked event) added up; order = first appearance; 0 ⇒ none. */
    static List<Share> shares(List<Allocation> allocations, Map<String, String> wallets) {
        Map<String, long[]> acc = new LinkedHashMap<>();
        for (Allocation a : allocations) {
            long[] v = acc.computeIfAbsent(a.identityId(), k -> new long[2]);
            v[0] += a.units();
            v[1] += a.lamports();
        }
        List<Share> out = new ArrayList<>();
        acc.forEach((id, v) -> {
            if (v[1] > 0) {
                out.add(new Share(id, wallets.get(id), v[0], v[1]));
            }
        });
        return out;
    }

    // ---- write -------------------------------------------------------------------------------------

    public Mono<Recorded> record(UUID ownerUserId, RevenueEventRequest req, boolean anchor) {
        if (!rewards.enabled()) {
            return Mono.error(new ControlPlaneExceptions.Conflict(
                    "revenue share is paid with the immediate-reward flow, which is disabled (cryptobot.pov.reward.enabled=false / POV_REWARD_ENABLED)"));
        }
        String kind = req.source().kind();
        String ref = req.source().ref().trim();
        boolean simulated = Boolean.TRUE.equals(req.simulated());
        if (PovRevenueEvent.SIMULATED.equals(kind) && !simulated) {
            return Mono.error(new ProofOfValueExceptions.Unprocessable("SIMULATED_SOURCE_NOT_FLAGGED",
                    "a SIMULATED source is a simulated economic result: simulated must be true"));
        }
        List<UUID> ids = req.linkedValueEventIds();
        if (new HashSet<>(ids).size() != ids.size()) {
            return Mono.error(new ControlPlaneExceptions.InvalidRequest("DUPLICATE_VALUE_EVENT", "a linked value event appears twice: " + ids));
        }
        String tenant = Receipts.tenantOf(ownerUserId);
        return checkSource(ownerUserId, kind, ref)
                .then(revenues.findBySource(tenant, kind, ref).map(Optional::of).defaultIfEmpty(Optional.empty()))
                .flatMap(existing -> {
                    if (existing.isPresent()) {
                        PovRevenueEvent e = existing.get();
                        if (!sameContent(e, req, simulated)) {
                            return Mono.error(new ControlPlaneExceptions.Conflict("revenue source " + kind + " " + ref
                                    + " is already recorded (revenue event " + e.id() + ") with a different amount, project or linked value events"));
                        }
                        return reconcile(e).flatMap(x -> view(ownerUserId, x)).map(v -> new Recorded(v, false));
                    }
                    return create(ownerUserId, tenant, req, kind, ref, simulated, anchor);
                });
    }

    /** {@code PROPOSAL}: the ref is a proposal of this owner that is EXECUTED — confirmed on the chain (directly or by the reconciler). */
    private Mono<Void> checkSource(UUID ownerUserId, String kind, String ref) {
        if (!PovRevenueEvent.PROPOSAL.equals(kind)) {
            return Mono.empty();
        }
        UUID proposalId;
        try {
            proposalId = UUID.fromString(ref);
        } catch (IllegalArgumentException e) {
            return Mono.error(new ProofOfValueExceptions.Unprocessable("INVALID_PROPOSAL_REF", "source.ref of a PROPOSAL must be the proposal id (a UUID)",
                    List.of(ref)));
        }
        return proposals.findByIdAndOwner(proposalId, ownerUserId)
                .switchIfEmpty(Mono.error(new ProofOfValueExceptions.Unprocessable("UNKNOWN_PROPOSAL", "no proposal " + proposalId + " for this owner")))
                .flatMap(p -> executed(p) ? Mono.<Void>empty()
                        : Mono.error(new ProofOfValueExceptions.Unprocessable("PROPOSAL_NOT_EXECUTED",
                                "a PROPOSAL source must be EXECUTED and confirmed on the chain (this one is " + p.status() + ")", List.of(ref))));
    }

    static boolean executed(ActionProposal p) {
        return p.status() == ProposalStatus.EXECUTED && p.execution() != null && p.execution().signature() != null;
    }

    private static boolean sameContent(PovRevenueEvent e, RevenueEventRequest req, boolean simulated) {
        return e.amountLamports() == req.amountLamports() && e.projectId().equals(req.projectId()) && e.simulated() == simulated
                && e.links().stream().map(PovRevenueEvent.Link::valueEventId).toList().equals(req.linkedValueEventIds());
    }

    private Mono<Recorded> create(UUID ownerUserId, String tenant, RevenueEventRequest req, String kind, String ref, boolean simulated, boolean anchor) {
        List<UUID> ids = req.linkedValueEventIds();
        return Flux.fromIterable(ids)
                .concatMap(id -> events.find(tenant, id).map(Optional::of).defaultIfEmpty(Optional.empty()).map(o -> Map.entry(id, o)))
                .collectList()
                .flatMap(found -> {
                    List<String> unknown = found.stream().filter(x -> x.getValue().isEmpty()).map(x -> x.getKey().toString()).toList();
                    if (!unknown.isEmpty()) {
                        return Mono.error(new ProofOfValueExceptions.Unprocessable("UNKNOWN_VALUE_EVENT", "linked value events must be recorded first",
                                unknown));
                    }
                    List<ValueEventRecord> linked = found.stream().map(x -> x.getValue().get()).toList();
                    return Flux.fromIterable(linked).concatMap(e -> valueEvents.view(ownerUserId, e)).collectList().flatMap(views -> {
                        List<String> notAnchored = views.stream().filter(v -> !ValueEventService.ANCHORED.equals(v.status()))
                                .map(v -> v.id() + " (" + v.status() + ")").toList();
                        if (!notAnchored.isEmpty()) {
                            return Mono.error(new ProofOfValueExceptions.Unprocessable("VALUE_EVENT_NOT_ANCHORED",
                                    "revenue is attributed only to ANCHORED value events", notAnchored));
                        }
                        return store(ownerUserId, tenant, req, kind, ref, simulated, linked);
                    });
                })
                .flatMap(p -> (anchor && p.created() ? anchorNow(p.event()) : Mono.just(p.event()))
                        .flatMap(e -> view(ownerUserId, e))
                        .map(v -> new Recorded(v, p.created())));
    }

    private record Pending(PovRevenueEvent event, boolean created) {}

    private Mono<Pending> store(UUID ownerUserId, String tenant, RevenueEventRequest req, String kind, String ref, boolean simulated,
            List<ValueEventRecord> linked) {
        Split split = split(req.amountLamports(), props.contributorShareBps(), props.protocolFeeBps(), linked);
        Set<String> who = new LinkedHashSet<>();
        split.allocations().forEach(a -> who.add(a.identityId()));
        return identities.findAll(tenant, who).collectMap(PovIdentity::id, i -> Optional.ofNullable(i.wallet())).flatMap(found -> {
            Map<String, String> wallets = new LinkedHashMap<>();
            found.forEach((id, w) -> w.ifPresent(x -> wallets.put(id, x)));
            List<Share> shares = shares(split.allocations(), wallets);
            Instant now = clock.instant();
            UUID id = UUID.randomUUID();
            List<PovPayout> rows = new ArrayList<>();
            for (int i = 0; i < shares.size(); i++) {
                Share s = shares.get(i);
                rows.add(PovPayout.ofRevenue(UUID.randomUUID(), id, tenant, i, s.identityId(), s.wallet(), s.lamports(),
                        s.wallet() == null ? PovPayout.UNFUNDED : PovPayout.PENDING, POLICY, now));
            }
            List<PovRevenueEvent.Link> links = new ArrayList<>();
            for (int i = 0; i < linked.size(); i++) {
                links.add(new PovRevenueEvent.Link(linked.get(i).id(), i, split.perEvent().getOrDefault(linked.get(i).id(), 0L), linked.get(i).title()));
            }
            PovRevenueEvent e = new PovRevenueEvent(id, tenant, req.projectId(), kind, ref, simulated, req.amountLamports(), POLICY,
                    props.contributorShareBps(), props.protocolFeeBps(), split.pool(), split.fee(), split.retained(), props.treasuryIdentityId(), null,
                    PovDistribution.IN_PROGRESS, now, now, links, rows);
            return revenues.insert(e)
                    .doOnNext(stored -> {
                        metrics.povRevenueEvent(kind, simulated, stored.amountLamports());
                        log.info("pov_revenue_event_recorded id={} source={}:{} simulated={} amount={} pool={} fee={} retained={} links={} payouts={}",
                                stored.id(), kind, ref, simulated, stored.amountLamports(), stored.contributorPoolLamports(), stored.protocolFeeLamports(),
                                stored.retainedLamports(), stored.links().size(), stored.payouts().size());
                    })
                    .flatMap(stored -> rewards.payAll(stored.id(), tenant, stored.payouts())
                            .flatMap(done -> issueReceipt(ownerUserId, stored.withPayouts(done), linked, split))
                            .map(done -> new Pending(done, true)))
                    // A concurrent POST of the same source won the unique (tenant, source): hand its event back.
                    .onErrorResume(ex -> !(ex instanceof ControlPlaneExceptions.Conflict), ex -> revenues.findBySource(tenant, kind, ref)
                            .map(existing -> new Pending(existing, false))
                            .switchIfEmpty(Mono.error(ex)));
        });
    }

    /** The canonical revenue event (every allocation and payout as it ended), committed by a REVENUE_EVENT receipt. */
    private Mono<PovRevenueEvent> issueReceipt(UUID ownerUserId, PovRevenueEvent e, List<ValueEventRecord> linked, Split split) {
        String canonical = ValueEventCanonical.canonical(canonicalTree(e, linked, split));
        String hash = Digests.sha256(canonical);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("revenueEventId", e.id().toString());
        params.put("projectId", e.projectId());
        params.put("sourceKind", e.sourceKind());
        params.put("simulated", e.simulated());
        params.put("policy", POLICY);
        params.put("amountLamports", e.amountLamports());
        params.put("contributorPoolLamports", e.contributorPoolLamports());
        params.put("protocolFeeLamports", e.protocolFeeLamports());
        params.put("retainedLamports", e.retainedLamports());
        params.put("linkedValueEvents", linked.size());
        params.put("payouts", e.payouts().size());
        params.put("confirmedLamports", e.confirmedLamports());
        Instant now = clock.instant();
        ReceiptBody body = new ReceiptBody(null, ReceiptKind.REVENUE_EVENT, e.tenantId(), ownerUserId.toString(), AGENT,
                linked.stream().map(ValueEventRecord::receiptHash).toList(), List.of(), null, null, null, null, params,
                new ReceiptBody.Output(hash, SCHEMA, null), new ReceiptBody.Compute(null, null, 1, null), null, ReproducibilityLevel.L0_SIGNED,
                e.createdAt(), now, "pov-revenue:" + e.id(), null);
        String status = PovDistribution.statusOf(e.payouts());
        return receipts.issue(ReceiptDraft.of(body))
                .map(r -> e.with(status, r.receiptHash(), clock.instant()))
                .onErrorResume(ex -> {
                    // The payouts are what happened on the chain; a receipt that could not be issued does not undo them.
                    log.error("pov_revenue_receipt_failed id={} error={}", e.id(), ex.toString());
                    return Mono.just(e.with(status, null, clock.instant()));
                })
                .flatMap(done -> revenues.updateStatus(done).thenReturn(done))
                .doOnNext(done -> log.info("pov_revenue_event id={} status={} receipt={} confirmedLamports={} pool={}", done.id(), done.status(),
                        done.receiptHash(), done.confirmedLamports(), done.contributorPoolLamports()));
    }

    static Map<String, Object> canonicalTree(PovRevenueEvent e, List<ValueEventRecord> linked, Split split) {
        Map<String, Object> policy = new LinkedHashMap<>();
        policy.put("name", e.policy());
        policy.put("revenueShareBps", e.revenueShareBps());
        policy.put("protocolFeeBps", e.protocolFeeBps());
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("kind", e.sourceKind());
        source.put("ref", e.sourceRef());
        List<Map<String, Object>> ls = new ArrayList<>();
        for (ValueEventRecord v : linked) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("valueEventId", v.id().toString());
            m.put("receiptHash", v.receiptHash());
            m.put("valueEventHash", v.valueEventHash());
            m.put("shareLamports", split.perEvent().getOrDefault(v.id(), 0L));
            ls.add(m);
        }
        List<Map<String, Object>> as = new ArrayList<>();
        for (Allocation a : split.allocations()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("valueEventId", a.valueEventId().toString());
            m.put("identityId", a.identityId());
            m.put("role", a.role().name());
            m.put("units", a.units());
            m.put("lamports", a.lamports());
            as.add(m);
        }
        List<Map<String, Object>> ps = new ArrayList<>();
        for (PovPayout p : e.payouts()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("identityId", p.identityId());
            if (p.wallet() != null) {
                m.put("wallet", p.wallet());
            }
            m.put("lamports", p.lamports());
            m.put("status", p.status());
            if (p.txSignature() != null) {
                m.put("txSignature", p.txSignature());
            }
            ps.add(m);
        }
        Map<String, Object> tree = new LinkedHashMap<>();
        tree.put("schema", SCHEMA);
        tree.put("tenantId", e.tenantId());
        tree.put("revenueEventId", e.id().toString());
        tree.put("projectId", e.projectId());
        tree.put("source", source);
        tree.put("simulated", e.simulated());
        tree.put("asset", "SOL");
        tree.put("amountLamports", e.amountLamports());
        tree.put("policy", policy);
        tree.put("nominalPoolLamports", split.nominalPool());
        tree.put("contributorPoolLamports", e.contributorPoolLamports());
        tree.put("protocolFeeLamports", e.protocolFeeLamports());
        tree.put("retainedLamports", e.retainedLamports());
        if (e.treasuryIdentityId() != null) {
            tree.put("treasuryIdentityId", e.treasuryIdentityId());
        }
        tree.put("linkedValueEvents", ls);
        tree.put("allocations", as);
        tree.put("payouts", ps);
        return tree;
    }

    private Mono<PovRevenueEvent> anchorNow(PovRevenueEvent e) {
        if (e.receiptHash() == null) {
            return Mono.just(e);
        }
        return anchors.sweep(true)
                .doOnNext(r -> log.info("pov_revenue_anchor_sweep id={} receipt={} batch={} pending={}", e.id(), e.receiptHash(),
                        r.anchored() == null ? null : r.anchored().root(), r.pending()))
                .thenReturn(e);
    }

    // ---- read --------------------------------------------------------------------------------------

    public Mono<RevenueEventView> get(UUID ownerUserId, UUID id) {
        return revenues.find(Receipts.tenantOf(ownerUserId), id)
                .switchIfEmpty(Mono.error(new ControlPlaneExceptions.NotFound("Revenue event " + id)))
                .flatMap(this::reconcile)
                .flatMap(e -> view(ownerUserId, e));
    }

    public Flux<RevenueEventView> list(UUID ownerUserId, Integer limit) {
        int l = limit == null ? DEFAULT_LIMIT : Math.max(1, Math.min(limit, MAX_LIMIT));
        return revenues.findRecent(Receipts.tenantOf(ownerUserId), l).concatMap(this::reconcile).concatMap(e -> view(ownerUserId, e));
    }

    /** A SUBMITTED payout is looked up on the chain on every read (the V5 rule); the receipt recorded the event as it ended. */
    Mono<PovRevenueEvent> reconcile(PovRevenueEvent e) {
        if (!PovRewardService.needsReconcile(e.payouts())) {
            return Mono.just(e);
        }
        return rewards.reconcilePayouts(e.id(), e.payouts()).flatMap(ps -> {
            PovRevenueEvent next = e.withPayouts(ps);
            String status = PovDistribution.statusOf(ps);
            return status.equals(e.status()) ? Mono.just(next)
                    : Mono.just(next.with(status, e.receiptHash(), clock.instant())).flatMap(x -> revenues.updateStatus(x).thenReturn(x));
        });
    }

    Mono<RevenueEventView> view(UUID ownerUserId, PovRevenueEvent e) {
        Mono<Optional<AnchorRef>> anchor = e.receiptHash() == null ? Mono.just(Optional.empty())
                : receipts.require(ownerUserId, e.receiptHash()).flatMap(anchors::inclusion)
                        .map(inc -> inc.anchored() ? Optional.of(new AnchorRef(inc.root(), inc.tx(), inc.slot(), valueEvents.explorerUrl(inc)))
                                : Optional.<AnchorRef>empty())
                        .onErrorResume(ex -> Mono.just(Optional.empty()));
        return anchor.map(a -> new RevenueEventView(e.id(), e.projectId(), new RevenueSourceView(e.sourceKind(), e.sourceRef()), e.simulated(),
                e.amountLamports(), new RevenuePolicyView(e.policy(), e.revenueShareBps(), e.protocolFeeBps()), e.contributorPoolLamports(),
                e.protocolFeeLamports(), e.retainedLamports(), PovDistribution.statusOf(e.payouts()), e.receiptHash(), a.orElse(null),
                e.links().stream().map(l -> new LinkedValueEventView(l.valueEventId(), l.title(), l.shareLamports())).toList(),
                e.payouts().stream().map(PovRewardService::payoutView).toList(), e.confirmedLamports(), e.treasuryIdentityId(), e.createdAt()));
    }
}
