package io.lifeengine.cryptobot.proofofvalue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.lifeengine.cryptobot.application.controlplane.ControlPlaneExceptions;
import io.lifeengine.cryptobot.application.controlplane.Receipts;
import io.lifeengine.cryptobot.application.receipt.AnchorService;
import io.lifeengine.cryptobot.application.receipt.ReceiptDraft;
import io.lifeengine.cryptobot.application.receipt.ReceiptService;
import io.lifeengine.cryptobot.core.execution.ActionProposal;
import io.lifeengine.cryptobot.core.execution.ExecutionRecord;
import io.lifeengine.cryptobot.core.execution.ProposalStatus;
import io.lifeengine.cryptobot.core.receipts.Digests;
import io.lifeengine.cryptobot.core.receipts.IntelligenceReceipt;
import io.lifeengine.cryptobot.core.receipts.ReceiptBody;
import io.lifeengine.cryptobot.core.receipts.ReceiptKind;
import io.lifeengine.cryptobot.core.receipts.ReceiptSigningKey;
import io.lifeengine.cryptobot.core.receipts.ReproducibilityLevel;
import io.lifeengine.cryptobot.infrastructure.persistence.controlplane.ActionProposalRepository;
import io.lifeengine.cryptobot.observability.CryptobotMetrics;
import io.lifeengine.cryptobot.proofofvalue.ProofOfValueDtos.RevenueEventRequest;
import io.lifeengine.cryptobot.proofofvalue.ProofOfValueDtos.RevenueEventView;
import io.lifeengine.cryptobot.proofofvalue.ProofOfValueDtos.RevenueSourceRequest;
import io.lifeengine.cryptobot.proofofvalue.ProofOfValueDtos.ValueEventView;
import io.lifeengine.cryptobot.testsupport.InMemoryControlPlaneRepositories;
import io.lifeengine.cryptobot.testsupport.InMemoryPovRepositories;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;

/**
 * KAN-824 (V7) and KAN-825 (V8) without the network: the pure split of pov/revenue-share/v1 (two events with different units,
 * the exact sum, fee and retained, the floor's dust), the 409/422 refusals, the REVENUE_EVENT receipt, idempotency, and the
 * treasury's fold. The payout pipeline ({@link PovRewardService#payAll}) is a mock here and runs for real in
 * {@code ProofOfValueRevenueApiTest}.
 */
class PovRevenueServiceTest {

    static final Instant NOW = Instant.parse("2026-09-30T12:00:00Z");
    static final UUID OWNER = UUID.randomUUID();
    static final String TENANT = Receipts.tenantOf(OWNER);

    final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    final CryptobotMetrics metrics = new CryptobotMetrics(registry, List.of("SOL"));
    final ReceiptService receipts = new ReceiptService(InMemoryControlPlaneRepositories.receipts(), ReceiptSigningKey.generate("test-key"), metrics);
    final AnchorService anchors = mock(AnchorService.class);
    final ValueEventService valueEvents = mock(ValueEventService.class);
    final PovRewardService rewards = mock(PovRewardService.class);
    final ActionProposalRepository proposals = mock(ActionProposalRepository.class);

    ValueEventRecord a;
    ValueEventRecord b;

    @BeforeEach
    void setUp() {
        InMemoryControlPlaneRepositories.reset();
        InMemoryPovRepositories.reset();
        for (String id : List.of("sebas", "dev-agent-17", "review-agent-3", "cryptobot-001")) {
            InMemoryPovRepositories.IDENTITIES.put(TENANT + "|" + id,
                    new PovIdentity(TENANT, id, IdentityKind.AGENT, id + "-name", "sebas".equals(id) ? null : "W" + id, null, null, NOW));
        }
        a = event("A", List.of(c(0, "sebas", ContributionRole.SPECIFIER, 50), c(1, "dev-agent-17", ContributionRole.IMPLEMENTER, 50)));
        b = event("B", List.of(c(0, "sebas", ContributionRole.SPECIFIER, 34), c(1, "review-agent-3", ContributionRole.REVIEWER, 33),
                c(2, "cryptobot-001", ContributionRole.OPERATOR, 33)));
        when(rewards.enabled()).thenReturn(true);
        // Every PENDING payout confirms; UNFUNDED stays.
        when(rewards.payAll(any(), any(), anyList())).thenAnswer(inv -> {
            List<PovPayout> ps = inv.getArgument(2);
            return Mono.just(ps.stream().map(p -> PovPayout.PENDING.equals(p.status()) ? p.with(PovPayout.CONFIRMED, "sig-" + p.identityId(), "u", null, NOW)
                    : p).toList());
        });
        anchored(true);
        when(valueEvents.explorerUrl(any())).thenReturn(null);
        when(anchors.inclusion(any())).thenReturn(Mono.just(new AnchorService.Inclusion(false, null, null, null, null, null, List.of(), null, null)));
    }

    PovRevenueService service() {
        return new PovRevenueService(InMemoryPovRepositories.events(), InMemoryPovRepositories.revenues(), InMemoryPovRepositories.identities(),
                valueEvents, rewards, receipts, anchors, proposals, new PovRevenueProperties(null, null, null), metrics, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    // ---- the split ---------------------------------------------------------------------------------

    @Test
    @DisplayName("two events with different units: the pool is split pro rata to every contribution and the parts add up exactly")
    void splitAcrossTwoEvents() {
        PovRevenueService.Split s = PovRevenueService.split(50_000_000L, 2000, 500, List.of(a, b));
        assertThat(s.nominalPool()).isEqualTo(10_000_000L);
        assertThat(s.fee()).isEqualTo(2_500_000L);
        // 200 units in all: 50 → 2 500 000, 34 → 1 700 000, 33 → 1 650 000.
        assertThat(s.allocations()).extracting(PovRevenueService.Allocation::lamports)
                .containsExactly(2_500_000L, 2_500_000L, 1_700_000L, 1_650_000L, 1_650_000L);
        assertThat(s.pool()).isEqualTo(10_000_000L);
        assertThat(s.retained()).isEqualTo(37_500_000L);
        assertThat(s.pool() + s.fee() + s.retained()).isEqualTo(50_000_000L);
        assertThat(s.perEvent()).containsExactly(Map.entry(a.id(), 5_000_000L), Map.entry(b.id(), 5_000_000L));

        List<PovRevenueService.Share> shares = PovRevenueService.shares(s.allocations(), Map.of("dev-agent-17", "Wd", "review-agent-3", "Wr",
                "cryptobot-001", "Wc"));
        // sebas appears in both events: one payout of 4 200 000 (84 units), and no wallet ⇒ UNFUNDED later.
        assertThat(shares).extracting(PovRevenueService.Share::identityId).containsExactly("sebas", "dev-agent-17", "review-agent-3", "cryptobot-001");
        assertThat(shares).extracting(PovRevenueService.Share::lamports).containsExactly(4_200_000L, 2_500_000L, 1_650_000L, 1_650_000L);
        assertThat(shares.get(0).units()).isEqualTo(84);
        assertThat(shares.get(0).wallet()).isNull();
        assertThat(shares.stream().mapToLong(PovRevenueService.Share::lamports).sum()).isEqualTo(s.pool());
    }

    @Test
    @DisplayName("the floor's dust is retained: pool + fee + retained is always the amount, the pool never exceeds its nominal share")
    void floorDustIsRetained() {
        PovRevenueService.Split s = PovRevenueService.split(49_999_999L, 2000, 500, List.of(a, b));
        assertThat(s.nominalPool()).isEqualTo(9_999_999L);
        assertThat(s.fee()).isEqualTo(2_499_999L);
        assertThat(s.allocations()).extracting(PovRevenueService.Allocation::lamports)
                .containsExactly(2_499_999L, 2_499_999L, 1_699_999L, 1_649_999L, 1_649_999L);
        assertThat(s.pool()).isEqualTo(9_999_995L);
        assertThat(s.retained()).isEqualTo(49_999_999L - 2_499_999L - 9_999_995L);
        assertThat(s.pool() + s.fee() + s.retained()).isEqualTo(49_999_999L);
        assertThat(PovRevenueService.floorBps(1L, 2000)).isZero();
    }

    // ---- record ------------------------------------------------------------------------------------

    @Test
    @DisplayName("record: pool paid through the V5 flow, REVENUE_EVENT receipt child of both VALUE_EVENT receipts, simulated kept; same source ⇒ 200")
    void recordsPaysAndIssuesTheReceipt() {
        PovRevenueService.Recorded r = service().record(OWNER, request("SIMULATED", "demo-1", 50_000_000L, true, a.id(), b.id()), false).block();
        assertThat(r.created()).isTrue();
        RevenueEventView v = r.view();
        assertThat(v.simulated()).isTrue();
        assertThat(v.policy().name()).isEqualTo(PovRevenueService.POLICY);
        assertThat(v.policy().revenueShareBps()).isEqualTo(2000);
        assertThat(v.policy().protocolFeeBps()).isEqualTo(500);
        assertThat(v.contributorPoolLamports()).isEqualTo(10_000_000L);
        assertThat(v.protocolFeeLamports()).isEqualTo(2_500_000L);
        assertThat(v.retainedLamports()).isEqualTo(37_500_000L);
        assertThat(v.status()).isEqualTo("PARTIAL"); // sebas is UNFUNDED
        assertThat(v.payouts()).extracting(ProofOfValueDtos.PayoutView::status).containsExactly("UNFUNDED", "CONFIRMED", "CONFIRMED", "CONFIRMED");
        assertThat(v.confirmedLamports()).isEqualTo(5_800_000L);
        assertThat(v.linkedValueEvents()).extracting(ProofOfValueDtos.LinkedValueEventView::title).containsExactly("A", "B");
        assertThat(v.treasuryIdentityId()).isEqualTo("cryptobot-001");

        IntelligenceReceipt receipt = receipts.require(OWNER, v.receiptHash()).block();
        assertThat(receipt.kind()).isEqualTo(ReceiptKind.REVENUE_EVENT);
        assertThat(receipt.body().parents()).containsExactlyInAnyOrder(a.receiptHash(), b.receiptHash());
        assertThat(receipt.body().params()).containsEntry("simulated", true).containsEntry("amountLamports", 50_000_000L);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<PovPayout>> rows = ArgumentCaptor.forClass(List.class);
        verify(rewards).payAll(eq(v.id()), eq(TENANT), rows.capture());
        assertThat(rows.getValue()).allSatisfy(p -> {
            assertThat(p.revenueEventId()).isEqualTo(v.id());
            assertThat(p.valueEventId()).isNull();
            assertThat(p.distributionId()).isNull();
            assertThat(p.policy()).isEqualTo(PovRevenueService.POLICY);
        });
        assertThat(registry.get("pov.revenue.events").tag("source", "simulated").tag("simulated", "true").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("pov.revenue.lamports").tag("source", "simulated").counter().count()).isEqualTo(50_000_000.0);

        PovRevenueService.Recorded again = service().record(OWNER, request("SIMULATED", "demo-1", 50_000_000L, true, a.id(), b.id()), false).block();
        assertThat(again.created()).isFalse();
        assertThat(again.view().id()).isEqualTo(v.id());
        assertThatThrownBy(() -> service().record(OWNER, request("SIMULATED", "demo-1", 1L, true, a.id(), b.id()), false).block())
                .isInstanceOf(ControlPlaneExceptions.Conflict.class);
    }

    @Test
    @DisplayName("422 when a linked event is not anchored, and nothing is paid")
    void notAnchoredIs422() {
        anchored(false);
        assertThatThrownBy(() -> service().record(OWNER, request("SIMULATED", "demo-2", 50_000_000L, true, a.id()), false).block())
                .isInstanceOfSatisfying(ProofOfValueExceptions.Unprocessable.class, e -> assertThat(e.code()).isEqualTo("VALUE_EVENT_NOT_ANCHORED"));
        verify(rewards, never()).payAll(any(), any(), anyList());
        assertThat(InMemoryPovRepositories.REVENUES).isEmpty();
    }

    @Test
    @DisplayName("refusals: reward disabled 409, SIMULATED not flagged 422, duplicate link 400, PROPOSAL unknown / not executed 422")
    void refusals() {
        UUID pid = UUID.randomUUID();
        when(proposals.findByIdAndOwner(pid, OWNER)).thenReturn(Mono.just(proposal(pid, ProposalStatus.SUBMITTED, "sig")));
        when(proposals.findByIdAndOwner(any(UUID.class), eq(OWNER))).thenAnswer(inv -> pid.equals(inv.getArgument(0))
                ? Mono.just(proposal(pid, ProposalStatus.SUBMITTED, "sig")) : Mono.empty());
        assertThatThrownBy(() -> service().record(OWNER, request("SIMULATED", "x", 1_000L, false, a.id()), false).block())
                .isInstanceOfSatisfying(ProofOfValueExceptions.Unprocessable.class, e -> assertThat(e.code()).isEqualTo("SIMULATED_SOURCE_NOT_FLAGGED"));
        assertThatThrownBy(() -> service().record(OWNER, request("EXTERNAL", "x", 1_000L, false, a.id(), a.id()), false).block())
                .isInstanceOfSatisfying(ControlPlaneExceptions.InvalidRequest.class, e -> assertThat(e.code()).isEqualTo("DUPLICATE_VALUE_EVENT"));
        assertThatThrownBy(() -> service().record(OWNER, request("PROPOSAL", "not-a-uuid", 1_000L, true, a.id()), false).block())
                .isInstanceOfSatisfying(ProofOfValueExceptions.Unprocessable.class, e -> assertThat(e.code()).isEqualTo("INVALID_PROPOSAL_REF"));
        assertThatThrownBy(() -> service().record(OWNER, request("PROPOSAL", UUID.randomUUID().toString(), 1_000L, true, a.id()), false).block())
                .isInstanceOfSatisfying(ProofOfValueExceptions.Unprocessable.class, e -> assertThat(e.code()).isEqualTo("UNKNOWN_PROPOSAL"));
        assertThatThrownBy(() -> service().record(OWNER, request("PROPOSAL", pid.toString(), 1_000L, true, a.id()), false).block())
                .isInstanceOfSatisfying(ProofOfValueExceptions.Unprocessable.class, e -> assertThat(e.code()).isEqualTo("PROPOSAL_NOT_EXECUTED"));
        when(rewards.enabled()).thenReturn(false);
        assertThatThrownBy(() -> service().record(OWNER, request("EXTERNAL", "x", 1_000L, false, a.id()), false).block())
                .isInstanceOf(ControlPlaneExceptions.Conflict.class);
        verify(rewards, never()).payAll(any(), any(), anyList());
    }

    @Test
    @DisplayName("an EXECUTED proposal (confirmed signature) is a valid source")
    void executedProposalIsAccepted() {
        UUID pid = UUID.randomUUID();
        when(proposals.findByIdAndOwner(pid, OWNER)).thenReturn(Mono.just(proposal(pid, ProposalStatus.EXECUTED, "sig")));
        RevenueEventView v = service().record(OWNER, request("PROPOSAL", pid.toString(), 1_000_000L, true, a.id()), false).block().view();
        assertThat(v.source().kind()).isEqualTo("PROPOSAL");
        assertThat(v.source().ref()).isEqualTo(pid.toString());
        assertThat(PovRevenueService.executed(proposal(pid, ProposalStatus.EXECUTED, null))).isFalse();
    }

    // ---- treasury fold (V8) ------------------------------------------------------------------------

    @Test
    @DisplayName("treasury: income/fee/retained of its revenue events, CONFIRMED payouts it paid, compute cost of the events it contributed to")
    void treasuryFold() {
        UUID rev = UUID.randomUUID();
        PovRevenueEvent mine = new PovRevenueEvent(rev, TENANT, "cryptobot", "SIMULATED", "r", true, 50_000_000L, PovRevenueService.POLICY, 2000, 500,
                10_000_000L, 2_500_000L, 37_500_000L, "cryptobot-001", null, "PARTIAL", NOW, NOW, List.of(), List.of());
        UUID dist = UUID.randomUUID();
        List<PovPayout> all = List.of(
                PovPayout.ofRevenue(UUID.randomUUID(), rev, TENANT, 0, "dev-agent-17", "W", 3_000_000L, PovPayout.CONFIRMED, PovRevenueService.POLICY, NOW),
                PovPayout.ofRevenue(UUID.randomUUID(), rev, TENANT, 1, "sebas", null, 1_000_000L, PovPayout.UNFUNDED, PovRevenueService.POLICY, NOW),
                PovPayout.ofRevenue(UUID.randomUUID(), UUID.randomUUID(), TENANT, 0, "x", "W", 9_000_000L, PovPayout.CONFIRMED, "p", NOW),
                new PovPayout(UUID.randomUUID(), dist, a.id(), TENANT, 0, "dev-agent-17", null, "W", 2_000_000L, PovPayout.CONFIRMED, "s", null, null,
                        PovRewardService.POLICY, NOW, NOW.plusSeconds(5)));
        ValueEventRecord withCompute = a.withAttribution(a.contributions(), List.of(), List.of(new ValueEventRecord.ComputeReceipt(UUID.randomUUID(), 0,
                "compute", "W", "n", "m", 1, 1, 1000, 4_730_000L, null)));
        ValueEventRecord cb = b.withAttribution(b.contributions(), List.of(), List.of(new ValueEventRecord.ComputeReceipt(UUID.randomUUID(), 0,
                "compute", "W", "n", "m", 1, 1, 1000, 1_000L, null)));

        TreasuryService.Totals asPayer = TreasuryService.fold("cryptobot-001", true, List.of(mine), all, List.of(withCompute, cb));
        assertThat(asPayer.income()).isEqualTo(50_000_000L);
        assertThat(asPayer.fee()).isEqualTo(2_500_000L);
        assertThat(asPayer.retained()).isEqualTo(37_500_000L);
        assertThat(asPayer.payouts()).isEqualTo(5_000_000L); // its revenue payout (3M) + the immediate reward it paid as payer (2M); not the other revenue event's
        assertThat(asPayer.compute()).isEqualTo(1_000L); // only event B has cryptobot-001 as contributor
        assertThat(asPayer.recent()).extracting(TreasuryService.Entry::kind).containsExactlyInAnyOrder("VALUE", "REVENUE", "PAYOUT", "PAYOUT");
        assertThat(asPayer.recent().get(0).kind()).isEqualTo("PAYOUT"); // newest first

        TreasuryService.Totals notPayer = TreasuryService.fold("cryptobot-001", false, List.of(mine), all, List.of());
        assertThat(notPayer.payouts()).isEqualTo(3_000_000L);
    }

    // ---- fixtures ----------------------------------------------------------------------------------

    private void anchored(boolean anchored) {
        doAnswer(inv -> {
            ValueEventRecord e = inv.getArgument(1);
            return Mono.just(new ValueEventView(e.id(), e.receiptHash(), e.valueEventHash(), null, null,
                    anchored ? ValueEventService.ANCHORED : ValueEventService.RECORDED, null, null, null, 100, List.of(), null, null, List.of(), List.of(),
                    null, null, e.title(), NOW, NOW, null, List.of()));
        }).when(valueEvents).view(any(), any());
    }

    private ValueEventRecord event(String title, List<ValueEventRecord.Contribution> cs) {
        IntelligenceReceipt r = receipts.issue(ReceiptDraft.of(new ReceiptBody(null, ReceiptKind.VALUE_EVENT, TENANT, OWNER.toString(), "t", List.of(),
                List.of(), null, null, null, null, Map.of(), new ReceiptBody.Output(Digests.sha256(title), "s", null),
                new ReceiptBody.Compute(null, null, 1, null), null, ReproducibilityLevel.L0_SIGNED, NOW, NOW, "pov:" + title, null))).block();
        ValueEventRecord e = new ValueEventRecord(UUID.randomUUID(), TENANT, OWNER, r.receiptHash(), Digests.sha256(title), "cryptobot", "KAN-824", title,
                Digests.sha256("a"), Digests.sha256("b"), NOW, DistributionPolicy.EQUAL_SPLIT_V1, 100, "{}", NOW, cs);
        InMemoryPovRepositories.EVENTS.put(e.id(), e);
        return e;
    }

    private static ValueEventRecord.Contribution c(int pos, String id, ContributionRole role, int units) {
        return new ValueEventRecord.Contribution(pos, id, role, units, null, null);
    }

    private static RevenueEventRequest request(String kind, String ref, long amount, boolean simulated, UUID... ids) {
        return new RevenueEventRequest("cryptobot", new RevenueSourceRequest(kind, ref), amount, List.of(ids), simulated);
    }

    private static ActionProposal proposal(UUID id, ProposalStatus status, String signature) {
        ExecutionRecord exec = signature == null ? null
                : new ExecutionRecord("confirmed", signature, null, null, NOW, NOW, "finalized", null, null, null, 0, null, 0, null);
        return new ActionProposal(id, UUID.randomUUID(), OWNER, "wallet", "devnet", status, "REBALANCE", "t", "r", "u", null, null, null, null, null,
                null, null, null, exec, null, null, NOW, NOW, NOW, null, null, 0);
    }
}
