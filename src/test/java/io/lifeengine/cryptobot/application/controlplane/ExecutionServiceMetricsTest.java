package io.lifeengine.cryptobot.application.controlplane;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.lifeengine.cryptobot.adapters.solana.SolanaCluster;
import io.lifeengine.cryptobot.adapters.solana.SolanaRpcClient;
import io.lifeengine.cryptobot.adapters.solana.SolanaRpcException;
import io.lifeengine.cryptobot.adapters.solana.tx.SolanaKeypair;
import io.lifeengine.cryptobot.domain.strategy.RebalanceLeg;
import io.lifeengine.cryptobot.domain.strategy.RebalancePlan;
import io.lifeengine.cryptobot.domain.transactions.ActionProposal;
import io.lifeengine.cryptobot.domain.transactions.AuditEvent;
import io.lifeengine.cryptobot.domain.transactions.PreparedTransaction;
import io.lifeengine.cryptobot.domain.transactions.ProposalStatus;
import io.lifeengine.cryptobot.domain.wallet.Wallet;
import io.lifeengine.cryptobot.integration.signer.SignerClient;
import io.lifeengine.cryptobot.observability.CryptobotMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

/**
 * KAN-425 — the trade funnel as seen by {@code ExecutionService}: {@code trade_submitted_total},
 * {@code trade_confirmed_total}, {@code trade_failed_total{stage}} and the confirmation latency
 * timer, on a {@link SimpleMeterRegistry}. Collaborators are mocked; the signature is real so the
 * service's own verification passes.
 */
class ExecutionServiceMetricsTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final CryptobotMetrics metrics = new CryptobotMetrics(registry, List.of("SOL", "USDC"));

    private final ProposalService proposals = mock(ProposalService.class);
    private final WalletService wallets = mock(WalletService.class);
    private final SimulationService simulation = mock(SimulationService.class);
    private final PolicyEngine policy = mock(PolicyEngine.class);
    private final SignerClient signer = mock(SignerClient.class);
    private final SolanaRpcClient rpc = mock(SolanaRpcClient.class);
    private final AuditService audit = mock(AuditService.class);

    private final SolanaKeypair keypair = SolanaKeypair.generate();
    private Wallet wallet;
    private ActionProposal approved;
    private PreparedTransaction tx;

    private ExecutionService service;

    @BeforeEach
    void setUp() {
        Instant now = Instant.parse("2026-09-15T12:00:00Z");
        wallet = new Wallet(UUID.randomUUID(), Fixtures.OWNER, keypair.publicKeyBase58(), SolanaCluster.DEVNET, "demo", now, now);
        byte[] message = "fake-solana-message".getBytes(StandardCharsets.UTF_8);
        tx = new PreparedTransaction("devnet", wallet.address(), Fixtures.VAULT, 2_000_000_000L, "blockhash", 1000,
                Base64.getEncoder().encodeToString(message), Base64.getEncoder().encodeToString(message), "transfer 2 SOL");
        RebalancePlan plan = new RebalancePlan(List.of(
                new RebalanceLeg(RebalanceLeg.Action.SELL, "SOL", "So111", new BigDecimal("2"), new BigDecimal("200"), new BigDecimal("70"), new BigDecimal("50"), "USDC")),
                new BigDecimal("1000"), Map.of(), Map.of(), new BigDecimal("200"), "SELL 2 SOL");
        approved = new ActionProposal(UUID.randomUUID(), wallet.id(), wallet.ownerUserId(), wallet.address(), "devnet",
                ProposalStatus.APPROVED, "REBALANCE", "t", null, "op", null, plan, null, null, null, null, tx, null, null, null, null,
                now.plusSeconds(600), now, now);

        when(proposals.require(eq(wallet.ownerUserId()), eq(approved.id()))).thenReturn(Mono.just(approved));
        when(proposals.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(wallets.require(eq(wallet.ownerUserId()), eq(wallet.id()))).thenReturn(Mono.just(wallet));
        when(policy.executionPreconditions(any())).thenReturn(List.of());
        when(simulation.prepareTransfer(eq(wallet), anyLong())).thenReturn(Mono.just(tx));
        when(audit.record(any(), any(), any(), anyString(), anyString(), any())).thenAnswer(inv ->
                Mono.just(new AuditEvent(UUID.randomUUID(), inv.getArgument(0), inv.getArgument(1), inv.getArgument(2), inv.getArgument(3), inv.getArgument(4), Map.of(), Instant.now())));

        service = new ExecutionService(proposals, wallets, simulation, policy, signer, rpc, audit, metrics);
    }

    private void signerSignsForReal() {
        byte[] message = Base64.getDecoder().decode(tx.messageBase64());
        byte[] sig = keypair.sign(message);
        byte[] wire = new byte[1 + 64 + message.length];
        wire[0] = 1;
        System.arraycopy(sig, 0, wire, 1, 64);
        System.arraycopy(message, 0, wire, 65, message.length);
        when(signer.sign(eq(approved.id()), anyString(), eq(wallet.address())))
                .thenReturn(Mono.just(new SignerClient.SignResponse(Base64.getEncoder().encodeToString(wire), keypair.publicKeyBase58(), null)));
    }

    private double count(String name, String... tags) {
        return registry.get(name).tags(tags).counter().count();
    }

    @Test
    @DisplayName("submitted then confirmed: funnel steps 3 and 4 and the latency timer")
    void submittedAndConfirmed() {
        when(rpc.simulateTransaction(eq(SolanaCluster.DEVNET), anyString(), eq(false)))
                .thenReturn(Mono.just(new SolanaRpcClient.SimulationResult(true, null, List.of(), 150L)));
        signerSignsForReal();
        when(rpc.sendTransaction(eq(SolanaCluster.DEVNET), anyString())).thenReturn(Mono.just("5igna7ure"));
        when(rpc.getSignatureStatus(eq(SolanaCluster.DEVNET), eq("5igna7ure")))
                .thenReturn(Mono.just(new SolanaRpcClient.SignatureStatus("5igna7ure", "confirmed", false, null)));

        ActionProposal done = service.execute(wallet.ownerUserId(), approved.id(), "op").block();

        assertThat(done.status()).isEqualTo(ProposalStatus.EXECUTED);
        assertThat(count("trade.submitted", "asset", "SOL")).isEqualTo(1);
        assertThat(count("trade.confirmed", "result", "confirmed", "asset", "SOL")).isEqualTo(1);
        assertThat(registry.get("solana.confirmation.latency").tag("result", "confirmed").tag("cluster", "devnet").timer().count()).isEqualTo(1);
        assertThat(registry.find("trade.failed").tag("asset", "SOL").counter()).isNull();
    }

    @Test
    @DisplayName("on-chain error after submission: submitted=1, failed{stage=onchain}=1, confirmed=0")
    void failedOnChain() {
        when(rpc.simulateTransaction(eq(SolanaCluster.DEVNET), anyString(), eq(false)))
                .thenReturn(Mono.just(new SolanaRpcClient.SimulationResult(true, null, List.of(), 150L)));
        signerSignsForReal();
        when(rpc.sendTransaction(eq(SolanaCluster.DEVNET), anyString())).thenReturn(Mono.just("5igna7ure"));
        when(rpc.getSignatureStatus(eq(SolanaCluster.DEVNET), eq("5igna7ure")))
                .thenReturn(Mono.just(new SolanaRpcClient.SignatureStatus("5igna7ure", "confirmed", true, "{\"InstructionError\":[0,\"Custom\"]}")));

        ActionProposal done = service.execute(wallet.ownerUserId(), approved.id(), "op").block();

        assertThat(done.status()).isEqualTo(ProposalStatus.FAILED);
        assertThat(count("trade.submitted", "asset", "SOL")).isEqualTo(1);
        assertThat(count("trade.failed", "stage", "onchain", "asset", "SOL")).isEqualTo(1);
        assertThat(registry.find("trade.confirmed").tag("asset", "SOL").counter()).isNull();
        assertThat(registry.get("solana.confirmation.latency").tag("result", "failed").timer().count()).isEqualTo(1);
    }

    @Test
    @DisplayName("pre-flight simulation refuses: failed{stage=preflight}, nothing submitted")
    void failedAtPreflight() {
        when(rpc.simulateTransaction(eq(SolanaCluster.DEVNET), anyString(), eq(false)))
                .thenReturn(Mono.just(new SolanaRpcClient.SimulationResult(false, "InsufficientFunds", List.of(), null)));

        ActionProposal done = service.execute(wallet.ownerUserId(), approved.id(), "op").block();

        assertThat(done.status()).isEqualTo(ProposalStatus.FAILED);
        assertThat(count("trade.failed", "stage", "preflight", "asset", "SOL")).isEqualTo(1);
        assertThat(registry.find("trade.submitted").tag("asset", "SOL").counter()).isNull();
    }

    @Test
    @DisplayName("signer refuses: failed{stage=sign}")
    void failedAtSign() {
        when(rpc.simulateTransaction(eq(SolanaCluster.DEVNET), anyString(), eq(false)))
                .thenReturn(Mono.just(new SolanaRpcClient.SimulationResult(true, null, List.of(), 150L)));
        when(signer.sign(eq(approved.id()), anyString(), eq(wallet.address())))
                .thenReturn(Mono.error(new SignerClient.SignerRefused("destination not allowed")));

        service.execute(wallet.ownerUserId(), approved.id(), "op").block();

        assertThat(count("trade.failed", "stage", "sign", "asset", "SOL")).isEqualTo(1);
    }

    @Test
    @DisplayName("RPC failure while broadcasting is counted as stage=rpc (the dependency, not the step)")
    void failedAtRpc() {
        when(rpc.simulateTransaction(eq(SolanaCluster.DEVNET), anyString(), eq(false)))
                .thenReturn(Mono.just(new SolanaRpcClient.SimulationResult(true, null, List.of(), 150L)));
        signerSignsForReal();
        when(rpc.sendTransaction(eq(SolanaCluster.DEVNET), anyString()))
                .thenReturn(Mono.error(new SolanaRpcException("sendTransaction", -32002, "Blockhash not found", null)));

        service.execute(wallet.ownerUserId(), approved.id(), "op").block();

        assertThat(count("trade.failed", "stage", "rpc", "asset", "SOL")).isEqualTo(1);
        assertThat(registry.find("trade.submitted").tag("asset", "SOL").counter()).isNull();
    }
}
