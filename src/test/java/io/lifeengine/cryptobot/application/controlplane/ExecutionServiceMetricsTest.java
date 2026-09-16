package io.lifeengine.cryptobot.application.controlplane;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import io.lifeengine.cryptobot.adapters.solana.SolanaCluster;
import io.lifeengine.cryptobot.adapters.solana.SolanaRpcClient;
import io.lifeengine.cryptobot.adapters.solana.SolanaRpcException;
import io.lifeengine.cryptobot.domain.transactions.ActionProposal;
import io.lifeengine.cryptobot.domain.transactions.ProposalStatus;
import io.lifeengine.cryptobot.integration.signer.SignerClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

/**
 * KAN-425 — the trade funnel as seen by {@code ExecutionService}: {@code trade_submitted_total},
 * {@code trade_confirmed_total}, {@code trade_failed_total{stage}} and the confirmation latency
 * timer. Runs on {@link ExecutionHarness} (real in-memory store, real signature).
 */
class ExecutionServiceMetricsTest {

    private final ExecutionHarness h = new ExecutionHarness();

    @Test
    @DisplayName("submitted then confirmed: funnel steps 3 and 4 and the latency timer")
    void submittedAndConfirmed() {
        String sig = h.signerSignsForReal();
        when(h.rpc.sendTransaction(eq(SolanaCluster.DEVNET), anyString())).thenReturn(Mono.just(sig));
        when(h.rpc.getSignatureStatus(eq(SolanaCluster.DEVNET), eq(sig)))
                .thenReturn(Mono.just(new SolanaRpcClient.SignatureStatus(sig, "confirmed", false, null)));

        ActionProposal done = h.service.execute(h.wallet.ownerUserId(), h.approved.id(), "op").block();

        assertThat(done.status()).isEqualTo(ProposalStatus.EXECUTED);
        assertThat(h.count("trade.submitted", "asset", "SOL")).isEqualTo(1);
        assertThat(h.count("trade.confirmed", "result", "confirmed", "asset", "SOL")).isEqualTo(1);
        assertThat(h.registry.get("solana.confirmation.latency").tag("result", "confirmed").tag("cluster", "devnet").timer().count()).isEqualTo(1);
        assertThat(h.registry.find("trade.failed").tag("asset", "SOL").counter()).isNull();
    }

    @Test
    @DisplayName("on-chain error after submission: submitted=1, failed{stage=onchain}=1, confirmed=0")
    void failedOnChain() {
        String sig = h.signerSignsForReal();
        when(h.rpc.sendTransaction(eq(SolanaCluster.DEVNET), anyString())).thenReturn(Mono.just(sig));
        when(h.rpc.getSignatureStatus(eq(SolanaCluster.DEVNET), eq(sig)))
                .thenReturn(Mono.just(new SolanaRpcClient.SignatureStatus(sig, "confirmed", true, "{\"InstructionError\":[0,\"Custom\"]}")));

        ActionProposal done = h.service.execute(h.wallet.ownerUserId(), h.approved.id(), "op").block();

        assertThat(done.status()).isEqualTo(ProposalStatus.FAILED);
        assertThat(h.count("trade.submitted", "asset", "SOL")).isEqualTo(1);
        assertThat(h.count("trade.failed", "stage", "onchain", "asset", "SOL")).isEqualTo(1);
        assertThat(h.registry.find("trade.confirmed").tag("asset", "SOL").counter()).isNull();
        assertThat(h.registry.get("solana.confirmation.latency").tag("result", "failed").timer().count()).isEqualTo(1);
    }

    @Test
    @DisplayName("pre-flight simulation refuses: failed{stage=preflight}, nothing submitted")
    void failedAtPreflight() {
        when(h.rpc.simulateTransaction(eq(SolanaCluster.DEVNET), anyString(), eq(false)))
                .thenReturn(Mono.just(new SolanaRpcClient.SimulationResult(false, "InsufficientFunds", java.util.List.of(), null)));

        ActionProposal done = h.service.execute(h.wallet.ownerUserId(), h.approved.id(), "op").block();

        assertThat(done.status()).isEqualTo(ProposalStatus.FAILED);
        assertThat(h.count("trade.failed", "stage", "preflight", "asset", "SOL")).isEqualTo(1);
        assertThat(h.registry.find("trade.submitted").tag("asset", "SOL").counter()).isNull();
    }

    @Test
    @DisplayName("signer refuses: failed{stage=sign}")
    void failedAtSign() {
        when(h.signer.sign(eq(h.approved.id()), anyString(), eq(h.wallet.address())))
                .thenReturn(Mono.error(new SignerClient.SignerRefused("destination not allowed")));

        h.service.execute(h.wallet.ownerUserId(), h.approved.id(), "op").block();

        assertThat(h.count("trade.failed", "stage", "sign", "asset", "SOL")).isEqualTo(1);
    }

    @Test
    @DisplayName("node rejects the broadcast (JSON-RPC error): counted as stage=rpc, nothing submitted")
    void failedAtRpc() {
        h.signerSignsForReal();
        when(h.rpc.sendTransaction(eq(SolanaCluster.DEVNET), anyString()))
                .thenReturn(Mono.error(new SolanaRpcException("sendTransaction", -32002, "Blockhash not found", null)));

        ActionProposal done = h.service.execute(h.wallet.ownerUserId(), h.approved.id(), "op").block();

        assertThat(done.status()).isEqualTo(ProposalStatus.FAILED);
        assertThat(h.count("trade.failed", "stage", "rpc", "asset", "SOL")).isEqualTo(1);
        assertThat(h.registry.find("trade.submitted").tag("asset", "SOL").counter()).isNull();
    }
}
