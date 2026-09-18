package io.lifeengine.cryptobot.application.controlplane;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import io.lifeengine.cryptobot.adapters.solana.SolanaCluster;
import io.lifeengine.cryptobot.application.receipt.ReceiptService;
import io.lifeengine.cryptobot.application.receipt.TenantSalts;
import io.lifeengine.cryptobot.domain.policy.PolicyDecision;
import io.lifeengine.cryptobot.domain.policy.PolicyInput;
import io.lifeengine.cryptobot.domain.policy.PolicyPredicate;
import io.lifeengine.cryptobot.domain.policy.PolicyVerdict;
import io.lifeengine.cryptobot.domain.receipt.ReceiptSigningKey;
import io.lifeengine.cryptobot.domain.reliability.TradeEvents;
import io.lifeengine.cryptobot.domain.strategy.RebalanceLeg;
import io.lifeengine.cryptobot.domain.strategy.RebalancePlan;
import io.lifeengine.cryptobot.domain.transactions.ActionProposal;
import io.lifeengine.cryptobot.domain.transactions.ProposalStatus;
import io.lifeengine.cryptobot.domain.wallet.Wallet;
import io.lifeengine.cryptobot.infrastructure.persistence.controlplane.ActionProposalRepository;
import io.lifeengine.cryptobot.integration.signer.SignerClient;
import io.lifeengine.cryptobot.integration.validator.ValidatorClient;
import io.lifeengine.cryptobot.observability.CryptobotMetrics;
import io.lifeengine.cryptobot.testsupport.InMemoryControlPlaneRepositories;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** KAN-438 — paper §19: approval starts a timelock; inside it a human can cancel; the TTL is pushed so the lock fits. */
class ProposalServiceTimelockTest {

    static final Instant NOW = Instant.parse("2026-09-18T12:00:00Z");

    final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    final CryptobotMetrics metrics = new CryptobotMetrics(registry, List.of("SOL", "USDC"));
    final ActionProposalRepository repo = InMemoryControlPlaneRepositories.proposals();
    final AuditService audit = new AuditService(InMemoryControlPlaneRepositories.audit());
    final PolicyEngine policy = new PolicyEngine(
            new PolicyProperties(true, "devnet", new BigDecimal("500"), new BigDecimal("50"), List.of("SOL", "USDC", "USDT"),
                    Duration.ofSeconds(60), Fixtures.VAULT, 2_000_000_000L, Duration.ofMinutes(30)),
            new AuthorizationProperties("test-policy-v1", new BigDecimal("100"), new BigDecimal("250"), new BigDecimal("2500"),
                    8_000, 100, 50, Duration.ofMinutes(15), List.of("REBALANCE")),
            new TimelockProperties(Duration.ZERO, Duration.ofMinutes(30), Duration.ofMinutes(30)),
            Clock.fixed(NOW, ZoneOffset.UTC));
    final ProposalService service;
    final Wallet wallet;

    ProposalServiceTimelockTest() {
        InMemoryControlPlaneRepositories.reset();
        wallet = new Wallet(UUID.randomUUID(), Fixtures.OWNER, "wallet", SolanaCluster.DEVNET, "demo", NOW, NOW);
        ReceiptService receipts = new ReceiptService(InMemoryControlPlaneRepositories.receipts(), ReceiptSigningKey.generate("test-key"), metrics);
        Receipts receiptOf = new Receipts(new TenantSalts("test-salt-secret".getBytes(StandardCharsets.UTF_8)),
                null, new com.fasterxml.jackson.databind.ObjectMapper());
        service = new ProposalService(repo, mock(PortfolioService.class), mock(RebalancePlanner.class), mock(RiskEngine.class),
                mock(SimulationService.class), policy, mock(SignerClient.class), mock(ValidatorClient.class), audit, metrics, receipts, receiptOf,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    ActionProposal awaiting(PolicyVerdict.Decision decision, Instant expiresAt) {
        RebalancePlan plan = new RebalancePlan(List.of(
                new RebalanceLeg(RebalanceLeg.Action.SELL, "SOL", "So111", new BigDecimal("2"), new BigDecimal("200"), new BigDecimal("70"), new BigDecimal("50"), "USDC")),
                new BigDecimal("1000"), Map.of(), Map.of(), new BigDecimal("200"), "SELL 2 SOL");
        PolicyVerdict verdict = new PolicyVerdict(decision, decision == PolicyVerdict.Decision.ALLOW ? PolicyVerdict.Escalation.NONE : PolicyVerdict.Escalation.REQUIRE_SECOND_AGENT,
                decision == PolicyVerdict.Decision.ALLOW ? PolicyVerdict.AutonomyTier.AUTONOMOUS : PolicyVerdict.AutonomyTier.SECOND_AGENT,
                List.of(), List.of(PolicyPredicate.values()), "test-policy-v1", policy.rules().hash(), "sha256:" + "1".repeat(64));
        PolicyDecision d = new PolicyDecision(true, true, List.of(), List.of(), List.of(), NOW, verdict, new PolicyInput(null, null));
        return repo.insert(new ActionProposal(UUID.randomUUID(), wallet.id(), wallet.ownerUserId(), wallet.address(), "devnet",
                ProposalStatus.AWAITING_APPROVAL, "REBALANCE", "t", null, "op", null, plan, null, null, d, null, null, null, null, null, null,
                expiresAt, NOW, NOW, null, 0)).block();
    }

    @Test
    @DisplayName("approve an ESCALATE verdict: executableAt = now + 30 min, expiry pushed to executableAt + window, audit says so")
    void approvalStartsTheLock() {
        ActionProposal p = awaiting(PolicyVerdict.Decision.ESCALATE, NOW.plus(Duration.ofMinutes(20)));

        ActionProposal approved = service.approve(wallet.ownerUserId(), p.id(), "human", "ok").block();

        assertThat(approved.status()).isEqualTo(ProposalStatus.APPROVED);
        assertThat(approved.approval().executableAt()).isEqualTo(NOW.plus(Duration.ofMinutes(30)));
        assertThat(approved.expiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(60)));
        assertThat(policy.executionPreconditions(approved)).singleElement().asString().contains("Timelock");
        assertThat(InMemoryControlPlaneRepositories.AUDIT.stream().filter(e -> ProposalService.EV_APPROVED.equals(e.eventType())).findFirst().orElseThrow().payload())
                .containsEntry("executableAt", NOW.plus(Duration.ofMinutes(30)).toString());
        assertThat(InMemoryControlPlaneRepositories.outboxOf(p.id()).get(0).eventType()).isEqualTo(TradeEvents.APPROVED);
    }

    @Test
    @DisplayName("approve an ALLOW verdict: no lock, the original expiry stands when it already fits")
    void allowTierIsImmediate() {
        ActionProposal p = awaiting(PolicyVerdict.Decision.ALLOW, NOW.plus(Duration.ofHours(2)));

        ActionProposal approved = service.approve(wallet.ownerUserId(), p.id(), "human", null).block();

        assertThat(approved.approval().executableAt()).isEqualTo(NOW);
        assertThat(approved.expiresAt()).isEqualTo(NOW.plus(Duration.ofHours(2)));
        assertThat(policy.executionPreconditions(approved)).isEmpty();
    }

    @Test
    @DisplayName("cancel inside the timelock: REJECTED, approval kept, CANCELLED audit + trade.cancelled; only APPROVED rows")
    void cancelInsideTheLock() {
        ActionProposal p = awaiting(PolicyVerdict.Decision.ESCALATE, NOW.plus(Duration.ofMinutes(20)));
        assertThatThrownBy(() -> service.cancel(wallet.ownerUserId(), p.id(), "human", "nope").block())
                .isInstanceOf(ControlPlaneExceptions.Conflict.class).hasMessageContaining("only APPROVED");
        service.approve(wallet.ownerUserId(), p.id(), "human", "ok").block();

        ActionProposal cancelled = service.cancel(wallet.ownerUserId(), p.id(), "human", "changed my mind").block();

        assertThat(cancelled.status()).isEqualTo(ProposalStatus.REJECTED);
        assertThat(cancelled.approval().by()).isEqualTo("human");
        assertThat(cancelled.approval().decision()).isEqualTo(io.lifeengine.cryptobot.domain.transactions.ApprovalRecord.Decision.APPROVED);
        assertThat(InMemoryControlPlaneRepositories.AUDIT.stream().filter(e -> p.id().equals(e.proposalId())).map(e -> e.eventType()).toList())
                .containsExactly(ProposalService.EV_APPROVED, ProposalService.EV_CANCELLED);
        assertThat(InMemoryControlPlaneRepositories.AUDIT.stream().filter(e -> ProposalService.EV_CANCELLED.equals(e.eventType())).findFirst().orElseThrow().payload())
                .containsEntry("insideTimelock", "true");
        // The in-memory outbox orders by a fixed clock: same instant, no stable order — assert the set.
        assertThat(InMemoryControlPlaneRepositories.outboxOf(p.id()).stream().map(e -> e.eventType()).toList())
                .containsExactlyInAnyOrder(TradeEvents.APPROVED, TradeEvents.CANCELLED);
        assertThat(policy.executionPreconditions(cancelled)).anySatisfy(s -> assertThat(s).contains("REJECTED, not APPROVED"));
        assertThat(registry.get("approvals").tag("result", "cancelled").counter().count()).isEqualTo(1);
        // Terminal now: a second cancel is a 409.
        assertThatThrownBy(() -> service.cancel(wallet.ownerUserId(), p.id(), "human", null).block()).isInstanceOf(ControlPlaneExceptions.Conflict.class);
    }
}
