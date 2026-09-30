package io.lifeengine.cryptobot.application.controlplane;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.lifeengine.cryptobot.solana.rpc.SolanaCluster;
import io.lifeengine.cryptobot.solana.rpc.SolanaRpcClient;
import io.lifeengine.cryptobot.core.execution.ActionProposal;
import io.lifeengine.cryptobot.core.execution.PreparedTransaction;
import io.lifeengine.cryptobot.core.execution.ProposalStatus;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

/**
 * (audit G1, §17): {@code SimulationService.prepareTransfer} reads {@code
 * cryptobot.policy.rebalance-vault} freshly at execution time; before {@link
 * ExecutionService#requireDestinationBound} nothing compared that rebuilt destination with what
 * the human approved. Runs on {@link ExecutionHarness} — same wiring as {@link
 * ExecutionServiceMetricsTest}, which this file borrows its "preflight" pattern from.
 */
class ExecutionServiceDestinationBindingTest {

    private static final int ITERATIONS = 15;
    private final Random random = new Random(599L);

    /** A copy of {@code base} with a different destination and/or lamports, everything else untouched. */
    private static PreparedTransaction drifted(PreparedTransaction base, String destination, long lamports) {
        return new PreparedTransaction(base.cluster(), base.feePayer(), destination, lamports, base.recentBlockhash(),
                base.lastValidBlockHeight(), base.unsignedTransactionBase64(), base.messageBase64(), "transfer " + lamports + " to " + destination);
    }

    @Test
    @DisplayName("AC: rebalance-vault changes between approve and execute ⇒ FAILED stage=preflight, nothing validated/signed/broadcast")
    void destinationDriftRefusesBeforeSigning() {
        ExecutionHarness h = new ExecutionHarness();
        PreparedTransaction driftedTx = drifted(h.tx, "AttackerVau1t1111111111111111111111111111", h.tx.lamports());
        when(h.simulation.prepareTransfer(eq(h.wallet), anyLong())).thenReturn(Mono.just(driftedTx));

        ActionProposal done = h.service.execute(h.wallet.ownerUserId(), h.approved.id(), "op").block();

        assertThat(done.status()).isEqualTo(ProposalStatus.FAILED);
        verify(h.validator, never()).authorize(any(), any());
        verify(h.signer, never()).sign(any(), anyString(), anyString(), any(), any());
        verify(h.rpc, never()).sendTransaction(any(), anyString());
        assertThat(h.auditTypes()).contains(ExecutionService.EV_FAILED)
                .doesNotContain(ExecutionService.EV_VALIDATED, ExecutionService.EV_SIGNED, ExecutionService.EV_SUBMITTED);
        assertThat(h.count("trade.failed", "stage", "preflight", "asset", "SOL")).isEqualTo(1);
    }

    @Test
    @DisplayName("AC: rebalance-vault changes between approve and execute (amount drifts, same destination) ⇒ FAILED stage=preflight")
    void lamportsDriftRefusesBeforeSigning() {
        ExecutionHarness h = new ExecutionHarness();
        PreparedTransaction driftedTx = drifted(h.tx, h.tx.destination(), h.tx.lamports() + 1_000_000L);
        when(h.simulation.prepareTransfer(eq(h.wallet), anyLong())).thenReturn(Mono.just(driftedTx));

        ActionProposal done = h.service.execute(h.wallet.ownerUserId(), h.approved.id(), "op").block();

        assertThat(done.status()).isEqualTo(ProposalStatus.FAILED);
        verify(h.signer, never()).sign(any(), anyString(), anyString(), any(), any());
        assertThat(h.count("trade.failed", "stage", "preflight", "asset", "SOL")).isEqualTo(1);
    }

    @Test
    @DisplayName("no drift (the common case): rebuilt transaction still matches the approved one, execution proceeds")
    void noDriftStillExecutes() {
        ExecutionHarness h = new ExecutionHarness();
        String sig = h.signerSignsForReal();
        when(h.rpc.sendTransaction(eq(SolanaCluster.DEVNET), anyString())).thenReturn(Mono.just(sig));
        when(h.rpc.getSignatureStatus(eq(SolanaCluster.DEVNET), eq(sig)))
                .thenReturn(Mono.just(new SolanaRpcClient.SignatureStatus(sig, "confirmed", false, null)));

        ActionProposal done = h.service.execute(h.wallet.ownerUserId(), h.approved.id(), "op").block();

        assertThat(done.status()).isEqualTo(ProposalStatus.EXECUTED);
    }

    // ---------------------------------------------------------------------------------------
    // property: "lo aprobado == lo firmado" — over N random (destination, lamports) pairs, the
    // signer is invoked only when the rebuilt transaction is byte-for-byte the approved one; any
    // drift, whatever its shape, refuses before the signer is ever asked.
    // ---------------------------------------------------------------------------------------
    @Test
    @DisplayName("property: for any (destination, lamports) the execution-time rebuild produces, the signer only ever sees the approved one")
    void signerOnlyEverSeesTheApprovedDestination() {
        for (int i = 0; i < ITERATIONS; i++) {
            ExecutionHarness h = new ExecutionHarness();
            boolean drift = random.nextBoolean();
            String destination = drift ? "Drift" + i + "Vau1t111111111111111111111111111111" : h.tx.destination();
            long lamports = drift && random.nextBoolean() ? h.tx.lamports() + 1 + random.nextInt(1_000_000) : h.tx.lamports();
            boolean actuallyDrifted = !destination.equals(h.tx.destination()) || lamports != h.tx.lamports();
            PreparedTransaction fresh = actuallyDrifted ? drifted(h.tx, destination, lamports) : h.tx;
            when(h.simulation.prepareTransfer(eq(h.wallet), anyLong())).thenReturn(Mono.just(fresh));
            if (!actuallyDrifted) {
                String sig = h.signerSignsForReal();
                when(h.rpc.sendTransaction(any(), anyString())).thenReturn(Mono.just(sig));
                when(h.rpc.getSignatureStatus(any(), eq(sig))).thenReturn(Mono.just(new SolanaRpcClient.SignatureStatus(sig, "confirmed", false, null)));
            }

            ActionProposal done = h.service.execute(h.wallet.ownerUserId(), h.approved.id(), "op-" + i).block();

            if (actuallyDrifted) {
                assertThat(done.status()).as("iteration %d destination=%s lamports=%d", i, destination, lamports).isEqualTo(ProposalStatus.FAILED);
                verify(h.signer, never()).sign(any(), anyString(), anyString(), any(), any());
            } else {
                assertThat(done.status()).as("iteration %d (no drift)", i).isEqualTo(ProposalStatus.EXECUTED);
            }
        }
    }

    @Test
    @DisplayName("retry re-runs the same binding check: a config drift discovered mid-retry also refuses")
    void retryAlsoBindsTheDestination() {
        ExecutionHarness h = new ExecutionHarness();
        String sig = h.signerSignsAnyMessage();
        // First run signs and broadcasts tx1, but the node never sees it (uncertain broadcast) so the row
        // stays in flight with a signature the reconciler will find unseen once its blockhash expires.
        when(h.rpc.sendTransaction(eq(SolanaCluster.DEVNET), anyString()))
                .thenReturn(Mono.error(new RuntimeException("timeout")));
        ActionProposal inFlight = h.service.execute(h.wallet.ownerUserId(), h.approved.id(), "op").block();
        assertThat(inFlight.status()).isEqualTo(ProposalStatus.EXECUTING);
        assertThat(inFlight.execution().hasSignature()).isTrue();

        // Between the failed broadcast and the reconciler's retry, config drifts.
        PreparedTransaction driftedTx = drifted(h.tx, "RetryDriftVau1t111111111111111111111111", h.tx.lamports());
        when(h.simulation.prepareTransfer(eq(h.wallet), anyLong())).thenReturn(Mono.just(driftedTx));

        ActionProposal retried = h.service.retry(inFlight, "reconciler").block();

        assertThat(retried.status()).isEqualTo(ProposalStatus.FAILED);
        assertThat(h.auditTypes()).doesNotContain(ExecutionService.EV_RETRIED);
    }
}
