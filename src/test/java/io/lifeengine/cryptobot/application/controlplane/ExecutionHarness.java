package io.lifeengine.cryptobot.application.controlplane;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.lifeengine.cryptobot.adapters.solana.SolanaCluster;
import io.lifeengine.cryptobot.adapters.solana.SolanaRpcClient;
import io.lifeengine.cryptobot.adapters.solana.tx.SolanaKeypair;
import io.lifeengine.cryptobot.application.receipt.ReceiptService;
import io.lifeengine.cryptobot.application.receipt.TenantSalts;
import io.lifeengine.cryptobot.domain.receipt.IntelligenceReceipt;
import io.lifeengine.cryptobot.domain.receipt.ReceiptSigningKey;
import io.lifeengine.cryptobot.domain.policy.PolicyDecision;
import io.lifeengine.cryptobot.domain.strategy.RebalanceLeg;
import io.lifeengine.cryptobot.domain.strategy.RebalancePlan;
import io.lifeengine.cryptobot.domain.transactions.ActionProposal;
import io.lifeengine.cryptobot.domain.transactions.ApprovalRecord;
import io.lifeengine.cryptobot.domain.transactions.PreparedTransaction;
import io.lifeengine.cryptobot.domain.transactions.ProposalStatus;
import io.lifeengine.cryptobot.domain.transactions.ProposalTransition;
import io.lifeengine.cryptobot.domain.wallet.Wallet;
import io.lifeengine.cryptobot.infrastructure.persistence.controlplane.ActionProposalRepository;
import io.lifeengine.cryptobot.integration.signer.SignerClient;
import io.lifeengine.cryptobot.observability.CryptobotMetrics;
import io.lifeengine.cryptobot.testsupport.InMemoryControlPlaneRepositories;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import reactor.core.publisher.Mono;

/**
 * Shared wiring for the execution tests: a real in-memory {@link ActionProposalRepository} (so the
 * optimistic guard, the audit trail and the outbox are exercised for real), a real keypair (so the
 * service's own signature verification passes), mocks for everything that would touch the network.
 */
final class ExecutionHarness {

    final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    final CryptobotMetrics metrics = new CryptobotMetrics(registry, List.of("SOL", "USDC"));

    final ActionProposalRepository repo = InMemoryControlPlaneRepositories.proposals();
    final ProposalService proposals = mock(ProposalService.class);
    final WalletService wallets = mock(WalletService.class);
    final SimulationService simulation = mock(SimulationService.class);
    final PolicyEngine policy = mock(PolicyEngine.class);
    final SignerClient signer = mock(SignerClient.class);
    final SolanaRpcClient rpc = mock(SolanaRpcClient.class);
    final AuditService audit = new AuditService(InMemoryControlPlaneRepositories.audit());
    // KAN-391: a real receipt pipeline (ephemeral key, in-memory store) so EXECUTION receipts are asserted, not mocked.
    final ReceiptService receiptService = new ReceiptService(InMemoryControlPlaneRepositories.receipts(),
            ReceiptSigningKey.generate("test-key"), metrics);
    final Receipts receiptOf = new Receipts(new TenantSalts("test-salt-secret".getBytes(StandardCharsets.UTF_8)),
            null, new com.fasterxml.jackson.databind.ObjectMapper());
    final ExecutionReceipts executionReceipts = new ExecutionReceipts(receiptService, receiptOf, metrics);

    final SolanaKeypair keypair = SolanaKeypair.generate();
    final Instant now = Instant.parse("2026-09-15T12:00:00Z");
    final Wallet wallet;
    final PreparedTransaction tx;
    final ActionProposal approved;
    final ExecutionService service;

    ExecutionHarness() {
        InMemoryControlPlaneRepositories.reset();
        wallet = new Wallet(UUID.randomUUID(), Fixtures.OWNER, keypair.publicKeyBase58(), SolanaCluster.DEVNET, "demo", now, now);
        byte[] message = "fake-solana-message".getBytes(StandardCharsets.UTF_8);
        tx = new PreparedTransaction("devnet", wallet.address(), Fixtures.VAULT, 2_000_000_000L, "blockhash", 1000,
                Base64.getEncoder().encodeToString(message), Base64.getEncoder().encodeToString(message), "transfer 2 SOL");
        RebalancePlan plan = new RebalancePlan(List.of(
                new RebalanceLeg(RebalanceLeg.Action.SELL, "SOL", "So111", new BigDecimal("2"), new BigDecimal("200"), new BigDecimal("70"), new BigDecimal("50"), "USDC")),
                new BigDecimal("1000"), Map.of(), Map.of(), new BigDecimal("200"), "SELL 2 SOL");
        PolicyDecision executable = new PolicyDecision(true, true, List.of(), List.of(), List.of(), now, null);
        ApprovalRecord approval = new ApprovalRecord(ApprovalRecord.Decision.APPROVED, "op", now, null);
        approved = repo.insert(new ActionProposal(UUID.randomUUID(), wallet.id(), wallet.ownerUserId(), wallet.address(), "devnet",
                ProposalStatus.APPROVED, "REBALANCE", "t", null, "op", null, plan, null, null, executable, null, tx, approval, null, null, null,
                now.plusSeconds(600), now, now, null, 0)).block();

        // ProposalService is a thin gateway here: reads and commits go to the real in-memory store.
        when(proposals.require(any(), any())).thenAnswer(inv -> repo.findByIdAndOwner(inv.getArgument(1), inv.getArgument(0))
                .switchIfEmpty(Mono.error(new ControlPlaneExceptions.NotFound("Proposal"))));
        when(proposals.commit(any())).thenAnswer(inv -> repo.commit(inv.<ProposalTransition>getArgument(0)));
        when(wallets.require(eq(wallet.ownerUserId()), eq(wallet.id()))).thenReturn(Mono.just(wallet));
        when(policy.executionPreconditions(any())).thenReturn(List.of());
        when(simulation.prepareTransfer(eq(wallet), anyLong())).thenReturn(Mono.just(tx));
        when(rpc.simulateTransaction(eq(SolanaCluster.DEVNET), anyString(), eq(false)))
                .thenReturn(Mono.just(new SolanaRpcClient.SimulationResult(true, null, List.of(), 150L)));

        service = new ExecutionService(proposals, wallets, simulation, policy, signer, rpc, audit, metrics, executionReceipts);
    }

    /** The signer signs the real message with the wallet key; returns the transaction id (base58 of the signature). */
    String signerSignsForReal() {
        byte[] message = Base64.getDecoder().decode(tx.messageBase64());
        byte[] sig = keypair.sign(message);
        byte[] wire = new byte[1 + 64 + message.length];
        wire[0] = 1;
        System.arraycopy(sig, 0, wire, 1, 64);
        System.arraycopy(message, 0, wire, 65, message.length);
        when(signer.sign(eq(approved.id()), anyString(), eq(wallet.address())))
                .thenReturn(Mono.just(new SignerClient.SignResponse(Base64.getEncoder().encodeToString(wire), keypair.publicKeyBase58(), null)));
        return io.lifeengine.cryptobot.adapters.solana.Base58.encode(sig);
    }

    ActionProposal current() {
        return repo.findByIdAndOwner(approved.id(), wallet.ownerUserId()).block();
    }

    double count(String name, String... tags) {
        return registry.get(name).tags(tags).counter().count();
    }

    List<String> auditTypes() {
        return InMemoryControlPlaneRepositories.AUDIT.stream().filter(e -> approved.id().equals(e.proposalId())).map(e -> e.eventType()).toList();
    }

    /** EXECUTION receipts of the proposal, oldest first. */
    List<IntelligenceReceipt> receipts() {
        return InMemoryControlPlaneRepositories.receiptsOf(approved.id());
    }

    List<String> outboxTypes() {
        return InMemoryControlPlaneRepositories.outboxOf(approved.id()).stream().map(e -> e.eventType()).toList();
    }
}
