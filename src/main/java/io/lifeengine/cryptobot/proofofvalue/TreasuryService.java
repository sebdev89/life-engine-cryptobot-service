package io.lifeengine.cryptobot.proofofvalue;

import io.lifeengine.cryptobot.application.controlplane.ControlPlaneExceptions;
import io.lifeengine.cryptobot.application.controlplane.PolicyEngine;
import io.lifeengine.cryptobot.application.controlplane.Receipts;
import io.lifeengine.cryptobot.application.receipt.AnchorService;
import io.lifeengine.cryptobot.application.receipt.ReceiptService;
import io.lifeengine.cryptobot.integration.signer.SignerClient;
import io.lifeengine.cryptobot.proofofvalue.ProofOfValueDtos.TreasuryEventView;
import io.lifeengine.cryptobot.proofofvalue.ProofOfValueDtos.TreasuryPoliciesView;
import io.lifeengine.cryptobot.proofofvalue.ProofOfValueDtos.TreasuryView;
import io.lifeengine.cryptobot.solana.rpc.SolanaCluster;
import io.lifeengine.cryptobot.solana.rpc.SolanaRpcClient;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Proof of Value V8 (KAN-825): an agent's Treasury — a read model, nothing is moved here.
 *
 * <ul>
 *   <li>{@code onChainBalanceLamports}: RPC {@code getBalance} of the identity's wallet on the execution cluster; {@code null} with
 *       {@code balanceNote} when it has no wallet or the RPC failed (the note never carries the RPC URL).
 *   <li>{@code incomeLamports}, {@code protocolFeeLamports}, {@code retainedLamports}: Σ over the revenue events whose treasury is this
 *       identity ({@code cryptobot.pov.revenue.treasury-identity-id} when they were recorded).
 *   <li>{@code contributorPayoutsLamports}: Σ CONFIRMED payouts it paid — its revenue events', and the immediate rewards' (V5) when it is
 *       the configured treasury identity: every PoV payout is paid on its behalf.
 *   <li>{@code computeCostMicroUsd}: Σ compute receipts of the ValueEvents it contributed to — cost, not value.
 * </ul>
 *
 * <p>Honesty: in the demo the signer pays from the demo wallet; the treasury per agent is accounting, not a separate wallet yet. No
 * unrestricted autonomy: every spend is a payout with a policy, the signer's cap and an audit trail.
 */
@Service
public class TreasuryService {

    private static final Logger log = LoggerFactory.getLogger(TreasuryService.class);

    static final int RECENT = 20;
    static final Duration RPC_TIMEOUT = Duration.ofSeconds(5);

    private final PovIdentityRepository identities;
    private final ValueEventRepository events;
    private final RevenueRepository revenues;
    private final PayoutRepository payouts;
    private final ReceiptService receipts;
    private final AnchorService anchors;
    private final SolanaRpcClient rpc;
    private final PolicyEngine policy;
    private final SignerClient signer;
    private final PovRewardProperties rewardProps;
    private final PovRevenueProperties revenueProps;

    public TreasuryService(PovIdentityRepository identities, ValueEventRepository events, RevenueRepository revenues, PayoutRepository payouts,
            ReceiptService receipts, AnchorService anchors, SolanaRpcClient rpc, PolicyEngine policy, SignerClient signer, PovRewardProperties rewardProps,
            PovRevenueProperties revenueProps) {
        this.identities = identities;
        this.events = events;
        this.revenues = revenues;
        this.payouts = payouts;
        this.receipts = receipts;
        this.anchors = anchors;
        this.rpc = rpc;
        this.policy = policy;
        this.signer = signer;
        this.rewardProps = rewardProps;
        this.revenueProps = revenueProps;
    }

    /** An entry of recentEvents before its anchor tx is looked up ({@code receiptHash} non-null ⇒ the tx is the anchor memo's). */
    record Entry(String kind, String id, long lamports, Instant at, String txSignature, String receiptHash) {}

    /** Everything but the balance, the signer's cap and the anchor txs: a pure fold (tested without the network). */
    record Totals(long income, long payouts, long fee, long compute, long retained, List<Entry> recent) {}

    public Mono<TreasuryView> get(UUID ownerUserId, String identityId) {
        String tenant = Receipts.tenantOf(ownerUserId);
        return identities.find(tenant, identityId)
                .switchIfEmpty(Mono.error(new ControlPlaneExceptions.NotFound("Identity " + identityId)))
                .flatMap(i -> Mono.zip(revenues.findByTreasury(tenant, i.id()).collectList(), payouts.findAll(tenant).collectList(),
                                events.findAll(tenant).collectList(), balance(i), signerMax())
                        .flatMap(t -> {
                            Totals totals = fold(i.id(), i.id().equals(revenueProps.treasuryIdentityId()), t.getT1(), t.getT2(), t.getT3());
                            return Flux.fromIterable(totals.recent()).concatMap(e -> anchorTx(ownerUserId, e)).collectList()
                                    .map(recent -> new TreasuryView(i.id(), i.wallet(), t.getT4().lamports(), t.getT4().note(), totals.income(),
                                            totals.payouts(), totals.fee(), totals.compute(), totals.retained(),
                                            new TreasuryPoliciesView(rewardProps.poolLamports(), revenueProps.contributorShareBps(),
                                                    revenueProps.protocolFeeBps(), t.getT5().orElse(null)),
                                            recent));
                        }));
    }

    static Totals fold(String identityId, boolean payer, List<PovRevenueEvent> mine, List<PovPayout> all, List<ValueEventRecord> events) {
        Set<UUID> revenueIds = mine.stream().map(PovRevenueEvent::id).collect(Collectors.toSet());
        List<PovPayout> paid = all.stream().filter(p -> p.isRevenue() ? revenueIds.contains(p.revenueEventId()) : payer).toList();
        long income = mine.stream().mapToLong(PovRevenueEvent::amountLamports).sum();
        long fee = mine.stream().mapToLong(PovRevenueEvent::protocolFeeLamports).sum();
        long retained = mine.stream().mapToLong(PovRevenueEvent::retainedLamports).sum();
        long payoutsLamports = paid.stream().filter(p -> PovPayout.CONFIRMED.equals(p.status())).mapToLong(PovPayout::lamports).sum();
        List<ValueEventRecord> contributed = events.stream()
                .filter(e -> e.contributions().stream().anyMatch(c -> c.identityId().equals(identityId))).toList();
        long compute = contributed.stream().flatMap(e -> e.computeReceipts().stream()).mapToLong(ValueEventRecord.ComputeReceipt::estimatedCostMicroUsd).sum();
        // VALUE: an event it contributed to, with what its immediate reward confirmed; REVENUE: its income; PAYOUT: what it paid.
        Map<UUID, Long> confirmedByEvent = new HashMap<>();
        all.stream().filter(p -> !p.isRevenue() && PovPayout.CONFIRMED.equals(p.status()))
                .forEach(p -> confirmedByEvent.merge(p.valueEventId(), p.lamports(), Long::sum));
        List<Entry> entries = new ArrayList<>();
        contributed.forEach(e -> entries.add(new Entry("VALUE", e.id().toString(), confirmedByEvent.getOrDefault(e.id(), 0L), e.acceptedAt(), null,
                e.receiptHash())));
        mine.forEach(r -> entries.add(new Entry("REVENUE", r.id().toString(), r.amountLamports(), r.createdAt(), null, r.receiptHash())));
        paid.stream().filter(p -> PovPayout.CONFIRMED.equals(p.status()) || PovPayout.SUBMITTED.equals(p.status()))
                .forEach(p -> entries.add(new Entry("PAYOUT", p.id().toString(), p.lamports(), p.updatedAt(), p.txSignature(), null)));
        List<Entry> recent = entries.stream().sorted(Comparator.comparing(Entry::at, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(Entry::id)).limit(RECENT).toList();
        return new Totals(income, payoutsLamports, fee, compute, retained, recent);
    }

    private Mono<TreasuryEventView> anchorTx(UUID ownerUserId, Entry e) {
        if (e.receiptHash() == null) {
            return Mono.just(new TreasuryEventView(e.kind(), e.id(), e.lamports(), e.at(), e.txSignature()));
        }
        return receipts.require(ownerUserId, e.receiptHash()).flatMap(anchors::inclusion)
                .map(inc -> Optional.ofNullable(inc.anchored() ? inc.tx() : null))
                .onErrorResume(ex -> Mono.just(Optional.empty()))
                .defaultIfEmpty(Optional.empty())
                .map(tx -> new TreasuryEventView(e.kind(), e.id(), e.lamports(), e.at(), tx.orElse(null)));
    }

    record Balance(Long lamports, String note) {}

    private Mono<Balance> balance(PovIdentity i) {
        if (i.wallet() == null) {
            return Mono.just(new Balance(null, "identity has no wallet"));
        }
        SolanaCluster cluster = SolanaCluster.parse(policy.properties().executionCluster());
        return rpc.getBalanceLamports(cluster, i.wallet())
                .timeout(RPC_TIMEOUT)
                .map(l -> new Balance(l, null))
                .onErrorResume(ex -> {
                    // The class only: a message may carry the RPC URL, and an RPC URL may carry an API key.
                    log.warn("pov_treasury_balance_unavailable identity={} error={}", i.id(), ex.getClass().getSimpleName());
                    return Mono.just(new Balance(null, "RPC getBalance failed (" + ex.getClass().getSimpleName() + ")"));
                })
                .defaultIfEmpty(new Balance(null, "RPC getBalance returned nothing"));
    }

    private Mono<Optional<Long>> signerMax() {
        return signer.identity()
                .map(o -> o.filter(id -> id.maxLamports() > 0).map(SignerClient.Identity::maxLamports))
                .onErrorResume(ex -> Mono.just(Optional.empty()))
                .defaultIfEmpty(Optional.empty());
    }
}
