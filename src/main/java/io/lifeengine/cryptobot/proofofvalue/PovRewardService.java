package io.lifeengine.cryptobot.proofofvalue;

import io.lifeengine.cryptobot.application.controlplane.ControlPlaneExceptions;
import io.lifeengine.cryptobot.application.controlplane.ExecutionService;
import io.lifeengine.cryptobot.application.controlplane.PolicyEngine;
import io.lifeengine.cryptobot.application.controlplane.Receipts;
import io.lifeengine.cryptobot.application.oracle.PriceOracleService;
import io.lifeengine.cryptobot.application.receipt.AnchorService;
import io.lifeengine.cryptobot.application.receipt.ReceiptDraft;
import io.lifeengine.cryptobot.application.receipt.ReceiptService;
import io.lifeengine.cryptobot.core.oracle.OracleConsensus;
import io.lifeengine.cryptobot.core.policy.DeterministicPolicyEngine;
import io.lifeengine.cryptobot.core.policy.PolicyInput;
import io.lifeengine.cryptobot.core.policy.PolicyVerdict;
import io.lifeengine.cryptobot.core.receipts.Digests;
import io.lifeengine.cryptobot.core.receipts.ReceiptBody;
import io.lifeengine.cryptobot.core.receipts.ReceiptKind;
import io.lifeengine.cryptobot.core.receipts.ReproducibilityLevel;
import io.lifeengine.cryptobot.integration.signer.SignerClient;
import io.lifeengine.cryptobot.observability.CryptobotMetrics;
import io.lifeengine.cryptobot.proofofvalue.ProofOfValueDtos.AnchorRef;
import io.lifeengine.cryptobot.proofofvalue.ProofOfValueDtos.DistributionView;
import io.lifeengine.cryptobot.proofofvalue.ProofOfValueDtos.PayoutView;
import io.lifeengine.cryptobot.solana.rpc.SolanaCluster;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Proof of Value V5: the immediate reward of an ANCHORED ValueEvent, paid in devnet SOL.
 *
 * <pre>
 *   POST /value-events/{id}/distribute
 *     ─▶ 409 unless the event is ANCHORED (and the reward is enabled)
 *     ─▶ policy pov/reward-pro-rata/v1: per identity, lamports = floor(units / totalUnits × pool); no wallet ⇒ UNFUNDED
 *     ─▶ pov_distribution + pov_payout (one transaction; unique per event ⇒ idempotent)
 *     ─▶ per payout, one at a time: (I, S) of the payout ─▶ DeterministicPolicyEngine (must ALLOW)
 *          ─▶ ExecutionService.submitTransfer: simulate ─▶ validator attests ─▶ signer signs ─▶ SUBMITTED persisted ─▶ broadcast ─▶ confirm
 *          ─▶ CONFIRMED (tx + explorer) | SUBMITTED (reconciled on read) | FAILED (error, and the next payout goes on)
 *     ─▶ VALUE_DISTRIBUTION receipt (parent: the VALUE_EVENT receipt) ─▶ [anchor=true] AnchorService.sweep(wait=true)
 * </pre>
 *
 * <p>The payout's facts: {@code strategy_id} = {@link PovRewardProperties#strategyId()} (the policy must enable it),
 * {@code asset} SOL, {@code trade_value} = the lamports at the oracle's SOL consensus (unknown price ⇒ DENY), slippage 0 (a
 * plain transfer swaps nothing), {@code asset_exposure_after} 0 (a payout buys no asset), {@code daily_exposure} = the
 * payouts of the last 24 h, {@code agent_permitted} = the reward is enabled, {@code nonce_unused} = the payout never left
 * PENDING, and the expiry in epoch seconds like a proposal's. An ESCALATE verdict is not enough: a payout is autonomous or it
 * does not happen.
 */
@Service
public class PovRewardService {

    private static final Logger log = LoggerFactory.getLogger(PovRewardService.class);

    public static final String POLICY = "pov/reward-pro-rata/v1";
    public static final String SCHEMA = "pov/distribution/v1";
    public static final String AGENT = "life-engine.proof-of-value.reward";
    static final String ASSET = "SOL";
    static final long LAMPORTS_PER_SOL = 1_000_000_000L;
    /** How long the payout's intent is valid (epoch seconds, the tick proposals use). */
    static final long VALID_FOR_SECONDS = 300;

    public record Distributed(DistributionView view, boolean created) {}

    /** One identity's share, before anything is stored. {@code wallet} null ⇒ UNFUNDED. */
    record Share(String identityId, String wallet, int units, long lamports) {}

    private final ValueEventRepository events;
    private final PovIdentityRepository identities;
    private final PayoutRepository payouts;
    private final ValueEventService valueEvents;
    private final ReceiptService receipts;
    private final AnchorService anchors;
    private final ExecutionService execution;
    private final PolicyEngine policy;
    private final PriceOracleService oracle;
    private final SignerClient signer;
    private final PovRewardProperties props;
    private final CryptobotMetrics metrics;
    private final Clock clock;

    @Autowired
    public PovRewardService(ValueEventRepository events, PovIdentityRepository identities, PayoutRepository payouts, ValueEventService valueEvents,
            ReceiptService receipts, AnchorService anchors, ExecutionService execution, PolicyEngine policy, PriceOracleService oracle, SignerClient signer,
            PovRewardProperties props, CryptobotMetrics metrics) {
        this(events, identities, payouts, valueEvents, receipts, anchors, execution, policy, oracle, signer, props, metrics, Clock.systemUTC());
    }

    PovRewardService(ValueEventRepository events, PovIdentityRepository identities, PayoutRepository payouts, ValueEventService valueEvents,
            ReceiptService receipts, AnchorService anchors, ExecutionService execution, PolicyEngine policy, PriceOracleService oracle, SignerClient signer,
            PovRewardProperties props, CryptobotMetrics metrics, Clock clock) {
        this.events = events;
        this.identities = identities;
        this.payouts = payouts;
        this.valueEvents = valueEvents;
        this.receipts = receipts;
        this.anchors = anchors;
        this.execution = execution;
        this.policy = policy;
        this.oracle = oracle;
        this.signer = signer;
        this.props = props;
        this.metrics = metrics;
        this.clock = clock;
    }

    // ---- the split (pure) --------------------------------------------------------------------------

    /**
     * {@code pov/reward-pro-rata/v1}: the units of every contribution of an identity are added up (one transaction per
     * destination), and {@code lamports = floor(units × pool / totalUnits)}. The sum never exceeds the pool; the floor's
     * remainder stays with the payer. An identity whose share rounds to 0 lamports gets no payout. Order = first appearance.
     */
    static List<Share> split(List<ValueEventRecord.Contribution> contributions, int totalUnits, long pool, Map<String, String> wallets) {
        Map<String, Integer> units = new LinkedHashMap<>();
        for (ValueEventRecord.Contribution c : contributions) {
            units.merge(c.identityId(), c.units(), Integer::sum);
        }
        List<Share> shares = new ArrayList<>();
        units.forEach((id, u) -> {
            long lamports = BigDecimal.valueOf(pool).multiply(BigDecimal.valueOf(u)).divide(BigDecimal.valueOf(totalUnits), 0, RoundingMode.FLOOR)
                    .longValueExact();
            if (lamports > 0) {
                shares.add(new Share(id, wallets.get(id), u, lamports));
            }
        });
        return shares;
    }

    // ---- write -------------------------------------------------------------------------------------

    public Mono<Distributed> distribute(UUID ownerUserId, UUID valueEventId, boolean anchor) {
        String tenant = Receipts.tenantOf(ownerUserId);
        return events.find(tenant, valueEventId)
                .switchIfEmpty(Mono.error(new ControlPlaneExceptions.NotFound("Value event " + valueEventId)))
                .flatMap(event -> payouts.findByEvent(tenant, valueEventId)
                        .flatMap(existing -> reconcile(existing).flatMap(d -> view(ownerUserId, d)).map(v -> new Distributed(v, false)))
                        .switchIfEmpty(Mono.defer(() -> create(ownerUserId, tenant, event, anchor))));
    }

    private Mono<Distributed> create(UUID ownerUserId, String tenant, ValueEventRecord event, boolean anchor) {
        if (!props.isEnabled()) {
            return Mono.error(new ControlPlaneExceptions.Conflict("immediate reward is disabled (cryptobot.pov.reward.enabled=false / POV_REWARD_ENABLED)"));
        }
        return valueEvents.view(ownerUserId, event).flatMap(v -> {
            if (!ValueEventService.ANCHORED.equals(v.status())) {
                return Mono.error(new ControlPlaneExceptions.Conflict("immediate reward requires an anchored ValueEvent (this one is " + v.status() + ")"));
            }
            Set<String> ids = new java.util.LinkedHashSet<>();
            event.contributions().forEach(c -> ids.add(c.identityId()));
            return identities.findAll(tenant, ids).collectMap(PovIdentity::id, i -> Optional.ofNullable(i.wallet())).flatMap(found -> {
                Map<String, String> wallets = new LinkedHashMap<>();
                found.forEach((id, w) -> w.ifPresent(x -> wallets.put(id, x)));
                List<Share> shares = split(event.contributions(), event.totalUnits(), props.poolLamports(), wallets);
                Instant now = clock.instant();
                UUID distributionId = UUID.randomUUID();
                List<PovPayout> rows = new ArrayList<>();
                for (int i = 0; i < shares.size(); i++) {
                    Share s = shares.get(i);
                    rows.add(new PovPayout(UUID.randomUUID(), distributionId, event.id(), tenant, i, s.identityId(), null, s.wallet(), s.lamports(),
                            s.wallet() == null ? PovPayout.UNFUNDED : PovPayout.PENDING, null, null, null, POLICY, now, now));
                }
                PovDistribution d = new PovDistribution(distributionId, tenant, event.id(), props.poolLamports(), POLICY, null, PovDistribution.IN_PROGRESS,
                        now, now, rows);
                return payouts.insert(d)
                        .flatMap(stored -> pay(ownerUserId, event, stored).map(done -> new Pending(done, true)))
                        // A concurrent POST won the unique (value_event_id): hand its distribution back.
                        .onErrorResume(ex -> !(ex instanceof ControlPlaneExceptions.Conflict), ex -> payouts.findByEvent(tenant, event.id())
                                .map(existing -> new Pending(existing, false))
                                .switchIfEmpty(Mono.error(ex)));
            });
        }).flatMap(r -> (anchor && r.created() ? anchorNow(r.distribution()) : Mono.just(r.distribution()))
                .flatMap(d -> view(ownerUserId, d))
                .map(view -> new Distributed(view, r.created())));
    }

    /** Internal carrier: the stored distribution before it becomes a view. */
    private record Pending(PovDistribution distribution, boolean created) {}

    /** Every payable payout, one after the other, then the VALUE_DISTRIBUTION receipt. */
    private Mono<PovDistribution> pay(UUID ownerUserId, ValueEventRecord event, PovDistribution d) {
        return payAll(d.id(), d.tenantId(), d.payouts()).flatMap(done -> issueReceipt(ownerUserId, event, d.withPayouts(done)));
    }

    /** Refused before anything is signed when the reward is off: a revenue event's payouts go through this same flow. */
    boolean enabled() {
        return props.isEnabled();
    }

    /**
     * The payout pipeline, shared by a V5 distribution and a V7 revenue event ({@code batchId} = the distribution or the
     * revenue event, for the logs): UNFUNDED rows are only counted; every PENDING one, in order, through {@link #payOne}. A
     * failure never cuts the rest. Returns every payout as it ended.
     */
    Mono<List<PovPayout>> payAll(UUID batchId, String tenant, List<PovPayout> rows) {
        rows.stream().filter(p -> PovPayout.UNFUNDED.equals(p.status())).forEach(p -> {
            metrics.povPayout(PovPayout.UNFUNDED, p.lamports());
            log.info("pov_payout_unfunded batch={} identity={} lamports={}", batchId, p.identityId(), p.lamports());
        });
        Instant now = clock.instant();
        Mono<Optional<SignerClient.Identity>> signerId = signer.identity();
        Mono<Optional<OracleConsensus>> sol = oracle.read(List.of(ASSET)).map(r -> r.of(ASSET).filter(OracleConsensus::accepted))
                .onErrorResume(ex -> {
                    log.warn("pov_payout_oracle_unavailable batch={} error={}", batchId, ex.toString());
                    return Mono.just(Optional.empty());
                });
        Mono<Long> prior = payouts.lamportsSince(tenant, now.minus(Duration.ofHours(24))).defaultIfEmpty(0L);
        return Mono.zip(signerId, sol, prior).flatMap(ctx -> {
            AtomicLong exposureLamports = new AtomicLong(ctx.getT3());
            return Flux.fromIterable(rows)
                    .concatMap(p -> PovPayout.PENDING.equals(p.status())
                            ? payOne(batchId, p, ctx.getT1(), ctx.getT2(), exposureLamports)
                            : Mono.just(p))
                    .collectList();
        });
    }

    private Mono<PovPayout> payOne(UUID batchId, PovPayout p, Optional<SignerClient.Identity> signerId, Optional<OracleConsensus> sol,
            AtomicLong exposureLamports) {
        Instant now = clock.instant();
        String refusal = preflight(p, signerId);
        if (refusal != null) {
            return failed(batchId, p, refusal);
        }
        SolanaCluster cluster = SolanaCluster.parse(policy.properties().executionCluster());
        PolicyInput input = facts(p, sol, exposureLamports.get(), now);
        PolicyVerdict verdict = DeterministicPolicyEngine.evaluate(policy.rules(), input);
        if (!verdict.allowed()) {
            return failed(batchId, p, "policy " + verdict.policyVersion() + " " + verdict.decision() + (verdict.escalation() == PolicyVerdict.Escalation.NONE ? ""
                    : " (" + verdict.escalation() + ": a payout is autonomous or it does not happen)") + " failed=" + verdict.failedPredicates());
        }
        String payer = signerId.get().publicKey();
        ExecutionService.Transfer transfer = new ExecutionService.Transfer(p.id(), cluster, payer, p.wallet(), p.lamports(), "contributor " + p.identityId(),
                verdict, input);
        return execution.submitTransfer(transfer, signature -> payouts.updatePayout(p.with(PovPayout.SUBMITTED, signature,
                        valueEvents.explorerTxUrl(signature), null, clock.instant())))
                .flatMap(r -> {
                    String status = switch (r.status()) {
                        case ExecutionService.TRANSFER_CONFIRMED -> PovPayout.CONFIRMED;
                        case ExecutionService.TRANSFER_SUBMITTED -> PovPayout.SUBMITTED;
                        default -> PovPayout.FAILED;
                    };
                    if (!PovPayout.FAILED.equals(status)) {
                        exposureLamports.addAndGet(p.lamports());
                    }
                    PovPayout next = p.with(status, r.signature(), valueEvents.explorerTxUrl(r.signature()), r.error(), clock.instant());
                    metrics.povPayout(status, p.lamports());
                    log.info("pov_payout batch={} identity={} wallet={} lamports={} status={} tx={} error={}", batchId, p.identityId(), p.wallet(),
                            p.lamports(), status, r.signature(), r.error());
                    return payouts.updatePayout(next).thenReturn(next);
                });
    }

    /** Refusals that need no policy: the emergency stop, the signer, its cluster and its per-transaction cap. */
    private String preflight(PovPayout p, Optional<SignerClient.Identity> signerId) {
        if (!policy.properties().executionEnabled()) {
            return "Emergency stop is active (cryptobot.policy.execution-enabled=false): nothing is signed";
        }
        if (signerId.isEmpty() || signerId.get().publicKey() == null) {
            return "Signer service unavailable or disabled";
        }
        SignerClient.Identity id = signerId.get();
        String cluster = policy.properties().executionCluster();
        if (id.cluster() != null && !id.cluster().isBlank() && !id.cluster().equalsIgnoreCase(cluster)) {
            return "The signer only signs for " + id.cluster() + "; payouts run on " + cluster;
        }
        if (id.maxLamports() > 0 && p.lamports() > id.maxLamports()) {
            return "Payout of " + p.lamports() + " lamports exceeds the signer's cap SIGNER_MAX_LAMPORTS=" + id.maxLamports();
        }
        if (p.wallet().equals(id.publicKey())) {
            return "The contributor's wallet is the payer itself";
        }
        return null;
    }

    /** {@code (I, S)} of one payout (see the class comment). Unknown price ⇒ trade value and oracle age unknown ⇒ DENY. */
    PolicyInput facts(PovPayout p, Optional<OracleConsensus> sol, long exposureLamports, Instant now) {
        Long tradeCents = sol.map(c -> cents(p.lamports(), c.priceUsd())).orElse(null);
        Long exposureCents = sol.map(c -> cents(exposureLamports, c.priceUsd())).orElse(null);
        Long oracleAge = sol.map(c -> c.asOf().isAfter(now) ? 0L : Duration.between(c.asOf(), now).getSeconds()).orElse(null);
        return new PolicyInput(
                new PolicyInput.IntentFacts(AGENT, props.strategyId(), policy.rules().version(), ASSET, tradeCents, 0, now.getEpochSecond() + VALID_FOR_SECONDS),
                new PolicyInput.StateFacts(exposureCents, 0, oracleAge, props.isEnabled(), PovPayout.PENDING.equals(p.status()), now.getEpochSecond()));
    }

    /** Lamports at a USD price → cents, rounded up: quantization never shrinks a payment. */
    static long cents(long lamports, BigDecimal priceUsd) {
        return BigDecimal.valueOf(lamports).multiply(priceUsd).movePointLeft(9).movePointRight(2).setScale(0, RoundingMode.CEILING).longValueExact();
    }

    private Mono<PovPayout> failed(UUID batchId, PovPayout p, String error) {
        PovPayout next = p.with(PovPayout.FAILED, null, null, error, clock.instant());
        metrics.povPayout(PovPayout.FAILED, p.lamports());
        log.warn("pov_payout batch={} identity={} lamports={} status=FAILED error={}", batchId, p.identityId(), p.lamports(), error);
        return payouts.updatePayout(next).thenReturn(next);
    }

    /** The canonical distribution (every payout as it ended), committed by a VALUE_DISTRIBUTION receipt child of the event's. */
    private Mono<PovDistribution> issueReceipt(UUID ownerUserId, ValueEventRecord event, PovDistribution d) {
        Map<String, Object> tree = canonicalTree(event, d);
        String canonical = ValueEventCanonical.canonical(tree);
        String hash = Digests.sha256(canonical);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("valueEventId", event.id().toString());
        params.put("policy", POLICY);
        params.put("poolLamports", d.poolLamports());
        params.put("payouts", d.payouts().size());
        params.put("confirmed", (int) d.payouts().stream().filter(p -> PovPayout.CONFIRMED.equals(p.status())).count());
        params.put("confirmedLamports", d.confirmedLamports());
        Instant now = clock.instant();
        ReceiptBody body = new ReceiptBody(null, ReceiptKind.VALUE_DISTRIBUTION, d.tenantId(), ownerUserId.toString(), AGENT, List.of(event.receiptHash()),
                List.of(), null, null, null, null, params, new ReceiptBody.Output(hash, SCHEMA, null), new ReceiptBody.Compute(null, null, 1, null), null,
                ReproducibilityLevel.L0_SIGNED, d.createdAt(), now, "pov-distribution:" + d.id(), null);
        String status = PovDistribution.statusOf(d.payouts());
        return receipts.issue(ReceiptDraft.of(body))
                .map(r -> d.with(status, r.receiptHash(), clock.instant()))
                .onErrorResume(ex -> {
                    // The payouts are what happened on the chain; a receipt that could not be issued does not undo them.
                    log.error("pov_distribution_receipt_failed distribution={} error={}", d.id(), ex.toString());
                    return Mono.just(d.with(status, null, clock.instant()));
                })
                .flatMap(done -> payouts.updateDistribution(done).thenReturn(done))
                .doOnNext(done -> log.info("pov_distribution id={} event={} status={} receipt={} confirmedLamports={} pool={}", done.id(), event.id(),
                        done.status(), done.receiptHash(), done.confirmedLamports(), done.poolLamports()));
    }

    static Map<String, Object> canonicalTree(ValueEventRecord event, PovDistribution d) {
        List<Map<String, Object>> ps = new ArrayList<>();
        Map<String, Integer> units = new LinkedHashMap<>();
        event.contributions().forEach(c -> units.merge(c.identityId(), c.units(), Integer::sum));
        for (PovPayout p : d.payouts()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("identityId", p.identityId());
            if (p.wallet() != null) {
                m.put("wallet", p.wallet());
            }
            m.put("units", units.getOrDefault(p.identityId(), 0));
            m.put("lamports", p.lamports());
            m.put("status", p.status());
            if (p.txSignature() != null) {
                m.put("txSignature", p.txSignature());
            }
            ps.add(m);
        }
        Map<String, Object> tree = new LinkedHashMap<>();
        tree.put("schema", SCHEMA);
        tree.put("tenantId", d.tenantId());
        tree.put("distributionId", d.id().toString());
        tree.put("valueEventId", event.id().toString());
        tree.put("valueEventReceiptHash", event.receiptHash());
        tree.put("valueEventHash", event.valueEventHash());
        tree.put("policy", d.policy());
        tree.put("poolLamports", d.poolLamports());
        tree.put("totalUnits", event.totalUnits());
        tree.put("asset", "SOL");
        tree.put("payouts", ps);
        return tree;
    }

    private Mono<PovDistribution> anchorNow(PovDistribution d) {
        if (d.receiptHash() == null) {
            return Mono.just(d);
        }
        return anchors.sweep(true)
                .doOnNext(r -> log.info("pov_distribution_anchor_sweep id={} receipt={} batch={} pending={}", d.id(), d.receiptHash(),
                        r.anchored() == null ? null : r.anchored().root(), r.pending()))
                .thenReturn(d);
    }

    // ---- read --------------------------------------------------------------------------------------

    public Mono<DistributionView> get(UUID ownerUserId, UUID valueEventId) {
        String tenant = Receipts.tenantOf(ownerUserId);
        return payouts.findByEvent(tenant, valueEventId)
                .switchIfEmpty(Mono.error(new ControlPlaneExceptions.NotFound("Distribution of value event " + valueEventId)))
                .flatMap(this::reconcile)
                .flatMap(d -> view(ownerUserId, d));
    }

    /**
     * A SUBMITTED payout (broadcast uncertain, or the confirmation poll gave up) is looked up on the chain once more on every
     * read: CONFIRMED or FAILED when the chain says so, else it stays SUBMITTED. The receipt is not re-issued: it recorded
     * the distribution as it ended, and the rows are the live state.
     */
    Mono<PovDistribution> reconcile(PovDistribution d) {
        if (!needsReconcile(d.payouts())) {
            return Mono.just(d);
        }
        return reconcilePayouts(d.id(), d.payouts()).flatMap(ps -> {
            PovDistribution next = d.withPayouts(ps);
            String status = PovDistribution.statusOf(ps);
            return status.equals(d.status()) ? Mono.just(next)
                    : Mono.just(next.with(status, d.receiptHash(), clock.instant())).flatMap(x -> payouts.updateDistribution(x).thenReturn(x));
        });
    }

    static boolean needsReconcile(List<PovPayout> ps) {
        return ps.stream().anyMatch(p -> PovPayout.SUBMITTED.equals(p.status()) && p.txSignature() != null);
    }

    /** Each SUBMITTED payout looked up on the chain once (shared with an internal ticket's revenue events); the rows are updated when it moved. */
    Mono<List<PovPayout>> reconcilePayouts(UUID batchId, List<PovPayout> ps) {
        SolanaCluster cluster = SolanaCluster.parse(policy.properties().executionCluster());
        return Flux.fromIterable(ps)
                .concatMap(p -> !PovPayout.SUBMITTED.equals(p.status()) || p.txSignature() == null ? Mono.just(p)
                        : execution.transferStatus(cluster, p.txSignature()).flatMap(r -> {
                            if (ExecutionService.TRANSFER_SUBMITTED.equals(r.status())) {
                                return Mono.just(p);
                            }
                            String status = ExecutionService.TRANSFER_CONFIRMED.equals(r.status()) ? PovPayout.CONFIRMED : PovPayout.FAILED;
                            PovPayout next = p.with(status, p.txSignature(), p.explorerUrl(), r.error(), clock.instant());
                            metrics.povPayout(status, p.lamports());
                            log.info("pov_payout_reconciled batch={} identity={} tx={} status={}", batchId, p.identityId(), p.txSignature(), status);
                            return payouts.updatePayout(next).thenReturn(next);
                        }))
                .collectList();
    }

    static PayoutView payoutView(PovPayout p) {
        return new PayoutView(p.identityId(), p.displayName(), p.wallet(), p.lamports(), p.status(), p.txSignature(), p.explorerUrl(), p.error());
    }

    Mono<DistributionView> view(UUID ownerUserId, PovDistribution d) {
        Mono<Optional<AnchorRef>> anchor = d.receiptHash() == null ? Mono.just(Optional.empty())
                : receipts.require(ownerUserId, d.receiptHash()).flatMap(anchors::inclusion)
                        .map(inc -> inc.anchored() ? Optional.of(new AnchorRef(inc.root(), inc.tx(), inc.slot(), valueEvents.explorerUrl(inc))) : Optional.<AnchorRef>empty())
                        .onErrorResume(ex -> Mono.just(Optional.empty()));
        return anchor.map(a -> new DistributionView(d.id(), d.valueEventId(), d.poolLamports(), d.policy(), PovDistribution.statusOf(d.payouts()), d.receiptHash(),
                a.orElse(null), d.confirmedLamports(),
                d.payouts().stream().map(PovRewardService::payoutView).toList(),
                d.createdAt()));
    }
}
