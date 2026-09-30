package io.lifeengine.cryptobot.proofofvalue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.lifeengine.cryptobot.application.controlplane.AuthorizationProperties;
import io.lifeengine.cryptobot.application.controlplane.ControlPlaneExceptions;
import io.lifeengine.cryptobot.application.controlplane.ExecutionService;
import io.lifeengine.cryptobot.application.controlplane.PolicyEngine;
import io.lifeengine.cryptobot.application.controlplane.PolicyProperties;
import io.lifeengine.cryptobot.application.controlplane.Receipts;
import io.lifeengine.cryptobot.application.controlplane.TimelockProperties;
import io.lifeengine.cryptobot.application.oracle.PriceOracleService;
import io.lifeengine.cryptobot.application.receipt.AnchorService;
import io.lifeengine.cryptobot.application.receipt.ReceiptDraft;
import io.lifeengine.cryptobot.application.receipt.ReceiptService;
import io.lifeengine.cryptobot.core.oracle.OracleConsensus;
import io.lifeengine.cryptobot.core.oracle.OracleLimits;
import io.lifeengine.cryptobot.core.oracle.OracleReading;
import io.lifeengine.cryptobot.core.policy.PolicyPredicate;
import io.lifeengine.cryptobot.core.policy.PolicyVerdict;
import io.lifeengine.cryptobot.core.receipts.Digests;
import io.lifeengine.cryptobot.core.receipts.IntelligenceReceipt;
import io.lifeengine.cryptobot.core.receipts.ReceiptBody;
import io.lifeengine.cryptobot.core.receipts.ReceiptKind;
import io.lifeengine.cryptobot.core.receipts.ReceiptSigningKey;
import io.lifeengine.cryptobot.core.receipts.ReproducibilityLevel;
import io.lifeengine.cryptobot.integration.signer.SignerClient;
import io.lifeengine.cryptobot.observability.CryptobotMetrics;
import io.lifeengine.cryptobot.proofofvalue.ProofOfValueDtos.DistributionView;
import io.lifeengine.cryptobot.proofofvalue.ProofOfValueDtos.PayoutView;
import io.lifeengine.cryptobot.proofofvalue.ProofOfValueDtos.ValueEventView;
import io.lifeengine.cryptobot.solana.tx.SolanaKeypair;
import io.lifeengine.cryptobot.testsupport.InMemoryControlPlaneRepositories;
import io.lifeengine.cryptobot.testsupport.InMemoryPovRepositories;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

/**
 * KAN-822 (Proof of Value V5, immediate reward) without the network: the split, the 409s, idempotency, UNFUNDED, a signer
 * refusal that does not stop the other payouts, the policy's DENY, the signer's cap, the VALUE_DISTRIBUTION receipt and the
 * reconciliation of a SUBMITTED payout. Real in-memory stores and receipts; the transfer pipeline itself
 * ({@link ExecutionService#submitTransfer}) is a mock here and runs for real in {@code ProofOfValueRewardApiTest}.
 */
class PovRewardServiceTest {

    static final Instant NOW = Instant.parse("2026-09-30T12:00:00Z");
    static final UUID OWNER = UUID.randomUUID();
    static final String TENANT = Receipts.tenantOf(OWNER);
    static final String REFUSED = "Signer refused: HTTP 403 {\"reason\":\"destination_not_allowed\"}";

    final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    final CryptobotMetrics metrics = new CryptobotMetrics(registry, List.of("SOL"));
    final ReceiptService receipts = new ReceiptService(InMemoryControlPlaneRepositories.receipts(), ReceiptSigningKey.generate("test-key"), metrics);
    final AnchorService anchors = mock(AnchorService.class);
    final ValueEventService valueEvents = mock(ValueEventService.class);
    final ExecutionService execution = mock(ExecutionService.class);
    final PriceOracleService oracle = mock(PriceOracleService.class);
    final SignerClient signer = mock(SignerClient.class);
    final String payer = SolanaKeypair.generate().publicKeyBase58();
    final String sebasWallet = SolanaKeypair.generate().publicKeyBase58();
    final String devWallet = SolanaKeypair.generate().publicKeyBase58();
    final String reviewWallet = SolanaKeypair.generate().publicKeyBase58();
    final List<ExecutionService.Transfer> transfers = new ArrayList<>();

    ValueEventRecord event;

    @BeforeEach
    void setUp() {
        InMemoryControlPlaneRepositories.reset();
        InMemoryPovRepositories.reset();
        identity("sebas", IdentityKind.HUMAN, sebasWallet);
        identity("dev-agent-17", IdentityKind.AGENT, devWallet);
        identity("review-agent-3", IdentityKind.AGENT, reviewWallet);
        identity("no-wallet", IdentityKind.HUMAN, null);
        IntelligenceReceipt eventReceipt = receipts.issue(ReceiptDraft.of(new ReceiptBody(null, ReceiptKind.VALUE_EVENT, TENANT, OWNER.toString(), "t",
                List.of(), List.of(), null, null, null, null, Map.of(), new ReceiptBody.Output(Digests.sha256("event"), "s", null),
                new ReceiptBody.Compute(null, null, 1, null), null, ReproducibilityLevel.L0_SIGNED, NOW, NOW, "pov:event", null))).block();
        // sebas has two roles (35 units in all): one payout, one transaction to one destination.
        event = new ValueEventRecord(UUID.randomUUID(), TENANT, OWNER, eventReceipt.receiptHash(), Digests.sha256("event"), "cryptobot", "KAN-822", "t",
                Digests.sha256("a"), Digests.sha256("b"), NOW, DistributionPolicy.EQUAL_SPLIT_V1, 100, "{}", NOW, List.of(
                        contribution(0, "sebas", ContributionRole.SPECIFIER, 25),
                        contribution(1, "dev-agent-17", ContributionRole.IMPLEMENTER, 25),
                        contribution(2, "review-agent-3", ContributionRole.REVIEWER, 25),
                        contribution(3, "sebas", ContributionRole.KNOWLEDGE_PROVIDER, 10),
                        contribution(4, "no-wallet", ContributionRole.OPERATOR, 15)));
        InMemoryPovRepositories.EVENTS.put(event.id(), event);

        anchored(true);
        when(valueEvents.explorerTxUrl(anyString())).thenAnswer(inv -> "https://explorer.solana.com/tx/" + inv.getArgument(0) + "?cluster=devnet");
        when(anchors.inclusion(any())).thenReturn(Mono.just(new AnchorService.Inclusion(false, null, null, null, null, null, List.of(), null, null)));
        when(signer.identity()).thenReturn(Mono.just(Optional.of(new SignerClient.Identity(payer, "devnet", 2_000_000_000L, List.of()))));
        when(oracle.read(any())).thenReturn(Mono.just(new OracleReading(NOW, new OracleLimits(2, 60, 100, 1_000, 300), List.of(
                new OracleConsensus("SOL", "So11111111111111111111111111111111111111112", new BigDecimal("150"), NOW.minusSeconds(10), List.of(), List.of(),
                        List.of(), List.of(), Digests.sha256("quotes"))))));
        // The signer refuses the reviewer's wallet (not in SIGNER_ALLOWED_DESTINATIONS); every other transfer confirms.
        doAnswer(inv -> {
            ExecutionService.Transfer t = inv.getArgument(0);
            Function<String, Mono<?>> onSigned = inv.getArgument(1);
            transfers.add(t);
            if (t.destination().equals(reviewWallet)) {
                return Mono.just(new ExecutionService.TransferResult(ExecutionService.TRANSFER_FAILED, null, null, REFUSED, "validator"));
            }
            String sig = "sig" + t.lamports();
            return onSigned.apply(sig).then(Mono.just(new ExecutionService.TransferResult(ExecutionService.TRANSFER_CONFIRMED, sig, "confirmed", null,
                    "validator")));
        }).when(execution).submitTransfer(any(), any());
    }

    PovRewardService service(boolean enabled, List<String> strategies) {
        PolicyEngine policy = new PolicyEngine(new PolicyProperties(true, "devnet", null, null, null, null, "", 0, null),
                new AuthorizationProperties(null, null, null, null, null, null, null, null, strategies), new TimelockProperties(null, null, null));
        return new PovRewardService(InMemoryPovRepositories.events(), InMemoryPovRepositories.identities(), InMemoryPovRepositories.payouts(), valueEvents,
                receipts, anchors, execution, policy, oracle, signer, new PovRewardProperties(enabled, 10_000_000L, null), metrics,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    PovRewardService service() {
        return service(true, List.of("REBALANCE", "POV_REWARD"));
    }

    @Test
    @DisplayName("split: floor(units × pool / total) per identity (roles added up), the sum never exceeds the pool, 0-lamport shares are dropped")
    void split() {
        List<PovRewardService.Share> shares = PovRewardService.split(event.contributions(), 100, 10_000_000L, Map.of("sebas", "w1"));
        assertThat(shares).extracting(PovRewardService.Share::identityId).containsExactly("sebas", "dev-agent-17", "review-agent-3", "no-wallet");
        assertThat(shares).extracting(PovRewardService.Share::lamports).containsExactly(3_500_000L, 2_500_000L, 2_500_000L, 1_500_000L);
        assertThat(shares.get(0).units()).isEqualTo(35);
        assertThat(shares.get(1).wallet()).isNull();

        // 34/33/33 of an odd pool: each floors, the remainder (2 lamports) stays with the payer.
        List<ValueEventRecord.Contribution> thirds = List.of(contribution(0, "a", ContributionRole.SPECIFIER, 34),
                contribution(1, "b", ContributionRole.IMPLEMENTER, 33), contribution(2, "c", ContributionRole.REVIEWER, 33));
        List<PovRewardService.Share> odd = PovRewardService.split(thirds, 100, 10_000_001L, Map.of());
        assertThat(odd).extracting(PovRewardService.Share::lamports).containsExactly(3_400_000L, 3_300_000L, 3_300_000L);
        assertThat(odd.stream().mapToLong(PovRewardService.Share::lamports).sum()).isLessThanOrEqualTo(10_000_001L);

        // A share that floors to 0 lamports gets no payout at all.
        assertThat(PovRewardService.split(List.of(contribution(0, "a", ContributionRole.SPECIFIER, 99), contribution(1, "b", ContributionRole.REVIEWER, 1)),
                100, 50, Map.of())).extracting(PovRewardService.Share::identityId).containsExactly("a");
    }

    @Test
    @DisplayName("distribute: CONFIRMED with tx and explorer, UNFUNDED without wallet, FAILED on a signer refusal without stopping the rest; receipt + idempotent")
    void distributesAndIsIdempotent() {
        PovRewardService.Distributed first = service().distribute(OWNER, event.id(), false).block();
        assertThat(first.created()).isTrue();
        DistributionView d = first.view();
        assertThat(d.status()).isEqualTo(PovDistribution.PARTIAL);
        assertThat(d.policy()).isEqualTo(PovRewardService.POLICY);
        assertThat(d.poolLamports()).isEqualTo(10_000_000L);
        assertThat(d.payouts()).extracting(PayoutView::identityId).containsExactly("sebas", "dev-agent-17", "review-agent-3", "no-wallet");
        assertThat(d.payouts()).extracting(PayoutView::status).containsExactly("CONFIRMED", "CONFIRMED", "FAILED", "UNFUNDED");
        assertThat(d.payouts().get(0).txSignature()).isEqualTo("sig3500000");
        assertThat(d.payouts().get(0).explorerUrl()).isEqualTo("https://explorer.solana.com/tx/sig3500000?cluster=devnet");
        assertThat(d.payouts().get(0).displayName()).isEqualTo("sebas-name");
        assertThat(d.payouts().get(2).error()).contains("destination_not_allowed");
        assertThat(d.payouts().get(2).txSignature()).isNull();
        assertThat(d.payouts().get(3).wallet()).isNull();
        assertThat(d.payouts().get(3).txSignature()).isNull();
        assertThat(d.confirmedLamports()).isEqualTo(6_000_000L);
        // The payouts that exist sum to at most the pool.
        assertThat(d.payouts().stream().mapToLong(PayoutView::lamports).sum()).isLessThanOrEqualTo(d.poolLamports());

        // One transfer per wallet (3), none for the UNFUNDED one; each with an ALLOW verdict over the POV_REWARD intent.
        assertThat(transfers).hasSize(3);
        assertThat(transfers).allSatisfy(t -> {
            assertThat(t.feePayer()).isEqualTo(payer);
            assertThat(t.verdict().decision()).isEqualTo(PolicyVerdict.Decision.ALLOW);
            assertThat(t.input().intent().strategyId()).isEqualTo("POV_REWARD");
            assertThat(t.input().intent().asset()).isEqualTo("SOL");
            assertThat(t.input().state().nonceUnused()).isTrue();
        });
        // 0.0035 SOL at $150 = $0.525 → 53 cents (rounded up); the second payout sees the first in its daily exposure.
        assertThat(transfers.get(0).input().intent().tradeValueCents()).isEqualTo(53L);
        assertThat(transfers.get(1).input().state().dailyExposureCents()).isEqualTo(53L);
        assertThat(transfers.get(0).operationId()).isNotEqualTo(transfers.get(1).operationId());

        // The VALUE_DISTRIBUTION receipt, child of the VALUE_EVENT receipt, commits to every payout.
        assertThat(d.receiptHash()).isNotNull();
        IntelligenceReceipt r = receipts.require(OWNER, d.receiptHash()).block();
        assertThat(r.kind()).isEqualTo(ReceiptKind.VALUE_DISTRIBUTION);
        assertThat(r.parents()).containsExactly(event.receiptHash());
        assertThat(r.body().params()).containsEntry("confirmedLamports", 6_000_000L).containsEntry("payouts", 4);
        PovDistribution stored = InMemoryPovRepositories.payouts().findByEvent(TENANT, event.id()).block();
        assertThat(r.body().output().hash()).isEqualTo(Digests.sha256(ValueEventCanonical.canonical(PovRewardService.canonicalTree(event, stored))));
        assertThat(stored.status()).isEqualTo(PovDistribution.PARTIAL);

        // Metrics: pov.payouts{status} and pov.payout.lamports{status}.
        assertThat(registry.get("pov.payouts").tag("status", "confirmed").counter().count()).isEqualTo(2.0);
        assertThat(registry.get("pov.payouts").tag("status", "failed").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("pov.payouts").tag("status", "unfunded").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("pov.payout.lamports").tag("status", "confirmed").counter().count()).isEqualTo(6_000_000.0);

        // Idempotent: the second call returns the same distribution and moves nothing.
        PovRewardService.Distributed again = service().distribute(OWNER, event.id(), false).block();
        assertThat(again.created()).isFalse();
        assertThat(again.view().id()).isEqualTo(d.id());
        assertThat(again.view().payouts()).extracting(PayoutView::status).containsExactly("CONFIRMED", "CONFIRMED", "FAILED", "UNFUNDED");
        verify(execution, times(3)).submitTransfer(any(), any());
        assertThat(InMemoryControlPlaneRepositories.RECEIPTS).hasSize(2);

        // GET reads the same thing; another tenant does not see it.
        assertThat(service().get(OWNER, event.id()).block().id()).isEqualTo(d.id());
        assertThatThrownBy(() -> service().get(UUID.randomUUID(), event.id()).block()).isInstanceOf(ControlPlaneExceptions.NotFound.class);
    }

    @Test
    @DisplayName("409 when the ValueEvent is not ANCHORED — nothing stored, nothing signed")
    void notAnchoredIs409() {
        anchored(false);
        assertThatThrownBy(() -> service().distribute(OWNER, event.id(), false).block())
                .isInstanceOf(ControlPlaneExceptions.Conflict.class)
                .hasMessageContaining("immediate reward requires an anchored ValueEvent");
        assertThat(InMemoryPovRepositories.DISTRIBUTIONS).isEmpty();
        verify(execution, never()).submitTransfer(any(), any());
    }

    @Test
    @DisplayName("409 when the reward is disabled; 404 for an unknown event")
    void disabledAndUnknown() {
        assertThatThrownBy(() -> service(false, List.of("REBALANCE", "POV_REWARD")).distribute(OWNER, event.id(), false).block())
                .isInstanceOf(ControlPlaneExceptions.Conflict.class).hasMessageContaining("disabled");
        assertThatThrownBy(() -> service().distribute(OWNER, UUID.randomUUID(), false).block()).isInstanceOf(ControlPlaneExceptions.NotFound.class);
        assertThat(InMemoryPovRepositories.DISTRIBUTIONS).isEmpty();
    }

    @Test
    @DisplayName("a policy that does not enable POV_REWARD denies every payout: FAILED with the predicate, nothing reaches the signer")
    void policyDenies() {
        DistributionView d = service(true, List.of("REBALANCE")).distribute(OWNER, event.id(), false).block().view();
        assertThat(d.status()).isEqualTo(PovDistribution.FAILED);
        assertThat(d.payouts()).extracting(PayoutView::status).containsExactly("FAILED", "FAILED", "FAILED", "UNFUNDED");
        assertThat(d.payouts().get(0).error()).contains("DENY").contains(PolicyPredicate.STRATEGY_ENABLED.name());
        verify(execution, never()).submitTransfer(any(), any());
    }

    @Test
    @DisplayName("an unknown SOL price is an unknown trade value: DENY, never a guess")
    void noPriceDenies() {
        when(oracle.read(any())).thenReturn(Mono.error(new IllegalStateException("oracle down")));
        DistributionView d = service().distribute(OWNER, event.id(), false).block().view();
        assertThat(d.payouts().get(0).status()).isEqualTo("FAILED");
        assertThat(d.payouts().get(0).error()).contains(PolicyPredicate.TRADE_WITHIN_MAX.name());
        verify(execution, never()).submitTransfer(any(), any());
    }

    @Test
    @DisplayName("a payout above SIGNER_MAX_LAMPORTS is FAILED before anything is signed; the next one goes on")
    void signerCap() {
        when(signer.identity()).thenReturn(Mono.just(Optional.of(new SignerClient.Identity(payer, "devnet", 3_000_000L, List.of()))));
        DistributionView d = service().distribute(OWNER, event.id(), false).block().view();
        assertThat(d.payouts().get(0).status()).isEqualTo("FAILED");
        assertThat(d.payouts().get(0).error()).contains("SIGNER_MAX_LAMPORTS=3000000");
        assertThat(d.payouts().get(1).status()).isEqualTo("CONFIRMED");
        assertThat(transfers).extracting(ExecutionService.Transfer::lamports).doesNotContain(3_500_000L);
    }

    @Test
    @DisplayName("no signer ⇒ every payable payout FAILED, nothing signed")
    void noSigner() {
        when(signer.identity()).thenReturn(Mono.just(Optional.empty()));
        DistributionView none = service().distribute(OWNER, event.id(), false).block().view();
        assertThat(none.status()).isEqualTo(PovDistribution.FAILED);
        assertThat(none.payouts().get(0).error()).contains("Signer service unavailable");
    }

    @Test
    @DisplayName("a SUBMITTED payout (confirmation still pending) is reconciled with the chain on the next read")
    void reconcilesSubmitted() {
        doAnswer(inv -> {
            ExecutionService.Transfer t = inv.getArgument(0);
            Function<String, Mono<?>> onSigned = inv.getArgument(1);
            String sig = "sig" + t.lamports();
            return onSigned.apply(sig).then(Mono.just(new ExecutionService.TransferResult(ExecutionService.TRANSFER_SUBMITTED, sig, "pending", null, "v")));
        }).when(execution).submitTransfer(any(), any());
        DistributionView d = service().distribute(OWNER, event.id(), false).block().view();
        assertThat(d.payouts()).extracting(PayoutView::status).containsExactly("SUBMITTED", "SUBMITTED", "SUBMITTED", "UNFUNDED");
        assertThat(d.status()).isEqualTo(PovDistribution.PARTIAL);

        when(execution.transferStatus(any(), anyString())).thenAnswer(inv -> Mono.just(new ExecutionService.TransferResult(
                ExecutionService.TRANSFER_CONFIRMED, inv.getArgument(1), "finalized", null, null)));
        DistributionView later = service().get(OWNER, event.id()).block();
        assertThat(later.payouts()).extracting(PayoutView::status).containsExactly("CONFIRMED", "CONFIRMED", "CONFIRMED", "UNFUNDED");
        assertThat(later.confirmedLamports()).isEqualTo(8_500_000L);
        assertThat(InMemoryPovRepositories.payouts().findByEvent(TENANT, event.id()).block().status()).isEqualTo(PovDistribution.PARTIAL);
    }

    @Test
    @DisplayName("?anchor=true runs the sweep once for the new VALUE_DISTRIBUTION receipt; not on an idempotent replay")
    void anchorRunsTheSweep() {
        when(anchors.sweep(anyBoolean())).thenReturn(Mono.empty());
        service().distribute(OWNER, event.id(), true).block();
        service().distribute(OWNER, event.id(), true).block();
        verify(anchors, times(1)).sweep(true);
    }

    @Test
    @DisplayName("the status is derived from the payouts")
    void statusOf() {
        PovPayout p = new PovPayout(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), TENANT, 0, "a", null, "w", 1, PovPayout.PENDING, null, null,
                null, PovRewardService.POLICY, NOW, NOW);
        assertThat(PovDistribution.statusOf(List.of(p))).isEqualTo(PovDistribution.IN_PROGRESS);
        assertThat(PovDistribution.statusOf(List.of(p.with("CONFIRMED", "s", null, null, NOW)))).isEqualTo(PovDistribution.COMPLETE);
        assertThat(PovDistribution.statusOf(List.of(p.with("CONFIRMED", "s", null, null, NOW), p.with("UNFUNDED", null, null, null, NOW))))
                .isEqualTo(PovDistribution.PARTIAL);
        assertThat(PovDistribution.statusOf(List.of(p.with("FAILED", null, null, "x", NOW), p.with("UNFUNDED", null, null, null, NOW))))
                .isEqualTo(PovDistribution.FAILED);
        assertThat(p.with("FAILED", null, null, "e".repeat(900), NOW).error()).hasSize(500);
    }

    // ---- fixtures ------------------------------------------------------------------------------

    private void anchored(boolean anchored) {
        when(valueEvents.view(any(), any())).thenReturn(Mono.just(new ValueEventView(event.id(), event.receiptHash(), event.valueEventHash(), null, null,
                anchored ? ValueEventService.ANCHORED : ValueEventService.RECORDED, null, null, null, 100, List.of(), null, null, List.of(), List.of(), null,
                null, null, NOW, NOW, null)));
    }

    private static void identity(String id, IdentityKind kind, String wallet) {
        InMemoryPovRepositories.IDENTITIES.put(TENANT + "|" + id, new PovIdentity(TENANT, id, kind, id + "-name", wallet, null, null, NOW));
    }

    private static ValueEventRecord.Contribution contribution(int position, String id, ContributionRole role, int units) {
        return new ValueEventRecord.Contribution(position, id, role, units, null, null);
    }
}
