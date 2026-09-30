package io.lifeengine.cryptobot.proofofvalue;

import io.lifeengine.cryptobot.application.controlplane.ControlPlaneExceptions;
import io.lifeengine.cryptobot.application.controlplane.Receipts;
import io.lifeengine.cryptobot.application.receipt.AnchorService;
import io.lifeengine.cryptobot.application.receipt.ReceiptService;
import io.lifeengine.cryptobot.proofofvalue.ProofOfValueDtos.HistoryEntryView;
import io.lifeengine.cryptobot.proofofvalue.ProofOfValueDtos.IdentityProfileView;
import io.lifeengine.cryptobot.proofofvalue.ProofOfValueDtos.IdentitySummaryView;
import io.lifeengine.cryptobot.proofofvalue.ProofOfValueDtos.LedgerRowView;
import io.lifeengine.cryptobot.proofofvalue.ProofOfValueDtos.LedgerView;
import io.lifeengine.cryptobot.proofofvalue.ProofOfValueDtos.ReputationView;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * The read models of V2 (reputation, history) and V6 (Contribution Units ledger), an internal ticket. Pure folds over
 * the stored contributions — no formula, no weights: a reputation is how many accepted outcomes and how many units;
 * the ledger adds up units by identity, by knowledge asset or by project, and its rows always sum to the units every
 * recorded event distributed.
 *
 * <p>By asset: the units of a KNOWLEDGE_PROVIDER contribution go to the assets of that event whose creator is that
 * contributor (split in equal parts, remainder to the first in request order); every other unit — and a knowledge
 * provider with no asset of theirs in the event — goes to the {@value #UNATTRIBUTED} row, so the total never moves.
 */
@Service
public class AttributionReadModel {

    public static final String UNATTRIBUTED = "unattributed";
    public static final List<String> GROUP_BY = List.of("identity", "asset", "project");

    private final ValueEventRepository events;
    private final PovIdentityRepository identities;
    private final KnowledgeAssetRepository assets;
    private final ReceiptService receipts;
    private final AnchorService anchors;
    private final PayoutRepository payouts;

    public AttributionReadModel(ValueEventRepository events, PovIdentityRepository identities, KnowledgeAssetRepository assets, ReceiptService receipts,
            AnchorService anchors, PayoutRepository payouts) {
        this.payouts = payouts;
        this.events = events;
        this.identities = identities;
        this.assets = assets;
        this.receipts = receipts;
        this.anchors = anchors;
    }

    // ---- V2: identities with reputation and history ---------------------------------------------

    public Flux<IdentitySummaryView> identities(UUID ownerUserId) {
        String tenant = Receipts.tenantOf(ownerUserId);
        return events.findAll(tenant).collectList().flatMapMany(all -> {
            Map<String, ReputationView> reps = reputations(all);
            return identities.findAll(tenant).map(i -> IdentitySummaryView.of(i, reps.getOrDefault(i.id(), ReputationView.NONE)));
        });
    }

    public Mono<IdentityProfileView> profile(UUID ownerUserId, String id) {
        String tenant = Receipts.tenantOf(ownerUserId);
        return identities.find(tenant, id)
                .switchIfEmpty(Mono.error(new ControlPlaneExceptions.NotFound("Identity " + id)))
                .flatMap(i -> events.findAll(tenant).collectList().flatMap(all -> {
                    ReputationView rep = reputations(all).getOrDefault(i.id(), ReputationView.NONE);
                    List<ValueEventRecord> mine = all.stream().filter(e -> e.contributions().stream().anyMatch(c -> c.identityId().equals(i.id())))
                            .sorted(Comparator.comparing(ValueEventRecord::acceptedAt).thenComparing(ValueEventRecord::createdAt).reversed())
                            .toList();
                    return Flux.fromIterable(mine)
                            .concatMap(e -> anchorStatus(ownerUserId, e).flatMapMany(status -> Flux.fromIterable(e.contributions())
                                    .filter(c -> c.identityId().equals(i.id()))
                                    .map(c -> new HistoryEntryView(e.id(), e.title(), c.role(), c.units(), e.acceptedAt(), status))))
                            .collectList()
                            .zipWith(rewards(tenant, i.id()))
                            .map(t -> new IdentityProfileView(i.id(), i.kind(), i.displayName(), i.wallet(), i.ownerId(), i.operatorId(),
                                    i.createdAt(), rep, t.getT1(), t.getT2()));
                }));
    }

    /**
     * (V5): lamports of the identity's CONFIRMED immediate-reward payouts, and how many payouts it has in any state;
     * (V7): the CONFIRMED revenue-share payouts, apart.
     */
    private Mono<ProofOfValueDtos.RewardsView> rewards(String tenant, String identityId) {
        return payouts.findByIdentity(tenant, identityId).collectList()
                .map(ps -> new ProofOfValueDtos.RewardsView(
                        ps.stream().filter(p -> !p.isRevenue() && PovPayout.CONFIRMED.equals(p.status())).mapToLong(PovPayout::lamports).sum(), ps.size(),
                        ps.stream().filter(p -> p.isRevenue() && PovPayout.CONFIRMED.equals(p.status())).mapToLong(PovPayout::lamports).sum()));
    }

    /** The event's RECORDED/ANCHORED, read from its receipt like {@code GET /value-events/{id}}. */
    private Mono<String> anchorStatus(UUID ownerUserId, ValueEventRecord e) {
        return receipts.require(ownerUserId, e.receiptHash()).flatMap(anchors::inclusion)
                .map(inc -> inc.anchored() ? ValueEventService.ANCHORED : ValueEventService.RECORDED)
                .defaultIfEmpty(ValueEventService.RECORDED);
    }

    static Map<String, ReputationView> reputations(List<ValueEventRecord> all) {
        Map<String, Set<UUID>> outcomes = new HashMap<>();
        Map<String, Long> units = new HashMap<>();
        Map<String, Instant> first = new HashMap<>();
        Map<String, Instant> last = new HashMap<>();
        for (ValueEventRecord e : all) {
            for (ValueEventRecord.Contribution c : e.contributions()) {
                outcomes.computeIfAbsent(c.identityId(), k -> new HashSet<>()).add(e.id());
                units.merge(c.identityId(), (long) c.units(), Long::sum);
                first.merge(c.identityId(), e.acceptedAt(), (a, b) -> a.isBefore(b) ? a : b);
                last.merge(c.identityId(), e.acceptedAt(), (a, b) -> a.isAfter(b) ? a : b);
            }
        }
        Map<String, ReputationView> out = new HashMap<>();
        outcomes.forEach((id, evs) -> out.put(id, new ReputationView(evs.size(), units.get(id), first.get(id), last.get(id))));
        return out;
    }

    // ---- V6: Contribution Units ledger ----------------------------------------------------------

    public Mono<LedgerView> ledger(UUID ownerUserId, String groupBy) {
        String g = groupBy == null || groupBy.isBlank() ? "identity" : groupBy.trim().toLowerCase(Locale.ROOT);
        if (!GROUP_BY.contains(g)) {
            return Mono.error(new ControlPlaneExceptions.InvalidRequest("INVALID_GROUP_BY", "groupBy must be one of " + GROUP_BY + " (got " + groupBy + ")"));
        }
        String tenant = Receipts.tenantOf(ownerUserId);
        return Mono.zip(events.findAll(tenant).collectList(), identities.findAll(tenant).collectMap(PovIdentity::id),
                        assets.findAll(tenant).collectMap(PovKnowledgeAsset::id))
                .map(t -> ledger(g, t.getT1(), t.getT2(), t.getT3()));
    }

    static LedgerView ledger(String groupBy, List<ValueEventRecord> all, Map<String, PovIdentity> ids, Map<String, PovKnowledgeAsset> assetsById) {
        Map<String, long[]> acc = new LinkedHashMap<>(); // key → {units, outcomes}
        long total = 0;
        for (ValueEventRecord e : all) {
            Map<String, Long> perKey = new LinkedHashMap<>();
            for (ValueEventRecord.Contribution c : e.contributions()) {
                total += c.units();
                switch (groupBy) {
                    case "identity" -> perKey.merge(c.identityId(), (long) c.units(), Long::sum);
                    case "project" -> perKey.merge(e.projectId(), (long) c.units(), Long::sum);
                    default -> {
                        List<String> theirs = c.role() != ContributionRole.KNOWLEDGE_PROVIDER ? List.of()
                                : e.knowledgeAssetIds().stream()
                                        .filter(a -> assetsById.containsKey(a) && assetsById.get(a).creatorId().equals(c.identityId())).toList();
                        if (theirs.isEmpty()) {
                            perKey.merge(UNATTRIBUTED, (long) c.units(), Long::sum);
                        } else {
                            long[] split = split(c.units(), theirs.size());
                            for (int i = 0; i < theirs.size(); i++) {
                                perKey.merge(theirs.get(i), split[i], Long::sum);
                            }
                        }
                    }
                }
            }
            if ("asset".equals(groupBy)) {
                e.knowledgeAssetIds().forEach(a -> perKey.putIfAbsent(a, 0L));
            }
            perKey.forEach((k, u) -> {
                long[] row = acc.computeIfAbsent(k, x -> new long[2]);
                row[0] += u;
                if (u > 0 || !UNATTRIBUTED.equals(k)) {
                    row[1]++;
                }
            });
        }
        List<LedgerRowView> rows = new ArrayList<>();
        acc.forEach((k, v) -> {
            if (UNATTRIBUTED.equals(k) && v[0] == 0) {
                return;
            }
            rows.add(switch (groupBy) {
                case "identity" -> {
                    PovIdentity i = ids.get(k);
                    yield new LedgerRowView(k, i == null ? k : i.displayName(), i == null ? null : i.kind().name(), v[0], v[1]);
                }
                case "project" -> new LedgerRowView(k, k, null, v[0], v[1]);
                default -> {
                    if (UNATTRIBUTED.equals(k)) {
                        yield new LedgerRowView(k, "Not attributed to a knowledge asset", null, v[0], v[1]);
                    }
                    PovKnowledgeAsset a = assetsById.get(k);
                    yield new LedgerRowView(k, a == null ? k : a.title(), a == null ? null : a.kind().name(), v[0], v[1]);
                }
            });
        });
        rows.sort(Comparator.comparing((LedgerRowView r) -> UNATTRIBUTED.equals(r.key()))
                .thenComparing(LedgerRowView::totalUnits, Comparator.reverseOrder())
                .thenComparing(LedgerRowView::key));
        return new LedgerView(groupBy, rows, total);
    }

    /** {@code units} in {@code n} equal parts, the remainder to the first (the rule of pov/equal-split/v1). */
    static long[] split(long units, int n) {
        long[] out = new long[n];
        long share = units / n;
        java.util.Arrays.fill(out, share);
        out[0] += units - share * n;
        return out;
    }
}
