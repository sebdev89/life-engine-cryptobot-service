package io.lifeengine.cryptobot.solana.rpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.lifeengine.cryptobot.core.Network;
import io.lifeengine.cryptobot.core.execution.MainnetRefusedException;
import io.lifeengine.cryptobot.core.execution.NetworkPolicy;
import io.lifeengine.cryptobot.core.ports.AssetPort;
import io.lifeengine.cryptobot.core.ports.ChainExecutionPort;
import io.lifeengine.cryptobot.core.ports.ChainObservationPort;
import io.lifeengine.cryptobot.core.ports.ChainSimulationPort;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

/**
 * (TAE phase 1, gap G9, chain ports) — the Solana implementation of the four chain ports
 * over a mocked {@link SolanaRpcClient}: every method maps 1:1 to the client's own call, and
 * {@code submit} carries its own mainnet gate ({@link NetworkPolicy}) independently of whichever
 * {@link SolanaRpcClient} bean is wired (chaos-decorated or not).
 */
class SolanaChainAdapterTest {

    final SolanaRpcClient rpc = mock(SolanaRpcClient.class);

    @Test
    void simulateMapsTheClientsResultAndHashesTheLogs() {
        when(rpc.simulateTransaction(SolanaCluster.DEVNET, "tx-base64", false))
                .thenReturn(Mono.just(new SolanaRpcClient.SimulationResult(true, null, List.of("log a", "log b"), 150L)));
        SolanaChainAdapter adapter = new SolanaChainAdapter(rpc, NetworkPolicy.failClosed());

        ChainSimulationPort.SimulationResult result = adapter.simulate(Network.DEVNET, "tx-base64").block();
        assertThat(result.ok()).isTrue();
        assertThat(result.unitsConsumed()).isEqualTo(150L);
        assertThat(result.logsHash()).isEqualTo(io.lifeengine.cryptobot.core.receipts.Digests.sha256("log a\nlog b"));
    }

    @Test
    void simulateWithNoLogsHasNoHash() {
        when(rpc.simulateTransaction(SolanaCluster.DEVNET, "tx-base64", false))
                .thenReturn(Mono.just(new SolanaRpcClient.SimulationResult(false, "err", List.of(), null)));
        SolanaChainAdapter adapter = new SolanaChainAdapter(rpc, NetworkPolicy.failClosed());

        ChainSimulationPort.SimulationResult result = adapter.simulate(Network.DEVNET, "tx-base64").block();
        assertThat(result.ok()).isFalse();
        assertThat(result.error()).isEqualTo("err");
        assertThat(result.logsHash()).isNull();
    }

    @Test
    void latestBlockhashDelegates() {
        when(rpc.getLatestBlockhash(SolanaCluster.DEVNET)).thenReturn(Mono.just(new SolanaRpcClient.LatestBlockhash("bh", 42L)));
        SolanaChainAdapter adapter = new SolanaChainAdapter(rpc, NetworkPolicy.failClosed());

        ChainExecutionPort.LatestBlockhash bh = adapter.latestBlockhash(Network.DEVNET).block();
        assertThat(bh.blockhash()).isEqualTo("bh");
        assertThat(bh.lastValidBlockHeight()).isEqualTo(42L);
    }

    @Test
    void submitOnDevnetDelegatesToTheClient() {
        when(rpc.sendTransaction(SolanaCluster.DEVNET, "signed-tx")).thenReturn(Mono.just("sig-1"));
        SolanaChainAdapter adapter = new SolanaChainAdapter(rpc, NetworkPolicy.failClosed());

        ChainExecutionPort.Submission submission = adapter.submit(Network.DEVNET, "signed-tx").block();
        assertThat(submission.signature()).isEqualTo("sig-1");
    }

    @Test
    void submitOnMainnetIsRefusedByTheCoreGateBeforeTheAdapterIsEvenCalled() {
        SolanaChainAdapter adapter = new SolanaChainAdapter(rpc, NetworkPolicy.failClosed());

        assertThatThrownBy(() -> adapter.submit(Network.MAINNET_BETA, "signed-tx").block())
                .isInstanceOf(MainnetRefusedException.class);
        verify(rpc, never()).sendTransaction(eq(SolanaCluster.MAINNET_BETA), anyString());
    }

    @Test
    void submitOnMainnetIsAllowedWhenTheCorePolicySaysSo() {
        when(rpc.sendTransaction(SolanaCluster.MAINNET_BETA, "signed-tx")).thenReturn(Mono.just("sig-mainnet"));
        SolanaChainAdapter adapter = new SolanaChainAdapter(rpc, new NetworkPolicy(true));

        ChainExecutionPort.Submission submission = adapter.submit(Network.MAINNET_BETA, "signed-tx").block();
        assertThat(submission.signature()).isEqualTo("sig-mainnet");
    }

    @Test
    void statusBlockHeightAndTransactionDelegate() {
        when(rpc.getSignatureStatus(SolanaCluster.DEVNET, "sig")).thenReturn(Mono.just(new SolanaRpcClient.SignatureStatus("sig", "finalized", false, null, 100L)));
        when(rpc.getBlockHeight(SolanaCluster.DEVNET)).thenReturn(Mono.just(200L));
        Instant t = Instant.parse("2026-09-27T00:00:00Z");
        when(rpc.getTransaction(SolanaCluster.DEVNET, "sig")).thenReturn(Mono.just(new SolanaRpcClient.TransactionInfo("sig", 100L, t, false, null, List.of("memo-1"))));
        SolanaChainAdapter adapter = new SolanaChainAdapter(rpc, NetworkPolicy.failClosed());

        ChainObservationPort.SignatureStatus status = adapter.status(Network.DEVNET, "sig").block();
        assertThat(status.confirmationStatus()).isEqualTo("finalized");
        assertThat(status.slot()).isEqualTo(100L);

        assertThat(adapter.blockHeight(Network.DEVNET).block()).isEqualTo(200L);

        ChainObservationPort.TransactionInfo info = adapter.transaction(Network.DEVNET, "sig").block();
        assertThat(info.slot()).isEqualTo(100L);
        assertThat(info.memos()).containsExactly("memo-1");
    }

    @Test
    void balanceAndTokenAccountsDelegate() {
        when(rpc.getBalanceLamports(SolanaCluster.DEVNET, "addr")).thenReturn(Mono.just(1_000_000_000L));
        when(rpc.getTokenAccountsByOwner(SolanaCluster.DEVNET, "addr")).thenReturn(Mono.just(List.of(
                new SolanaRpcClient.TokenAccountBalance("mint", "tokenAccount", "program", BigInteger.TEN, 6, BigDecimal.ONE))));
        SolanaChainAdapter adapter = new SolanaChainAdapter(rpc, NetworkPolicy.failClosed());

        assertThat(adapter.balanceLamports(Network.DEVNET, "addr").block()).isEqualTo(1_000_000_000L);
        List<AssetPort.TokenAccountBalance> accounts = adapter.tokenAccounts(Network.DEVNET, "addr").block();
        assertThat(accounts).singleElement().satisfies(a -> {
            assertThat(a.mint()).isEqualTo("mint");
            assertThat(a.amountRaw()).isEqualTo(BigInteger.TEN);
        });
    }

    @Test
    void nullNetworkPolicyDefaultsToFailClosed() {
        SolanaChainAdapter adapter = new SolanaChainAdapter(rpc, null);
        assertThatThrownBy(() -> adapter.submit(Network.MAINNET_BETA, "tx").block()).isInstanceOf(MainnetRefusedException.class);
    }
}
