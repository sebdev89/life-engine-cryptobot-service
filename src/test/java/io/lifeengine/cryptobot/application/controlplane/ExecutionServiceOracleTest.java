package io.lifeengine.cryptobot.application.controlplane;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.lifeengine.cryptobot.adapters.solana.SolanaCluster;
import io.lifeengine.cryptobot.adapters.solana.SolanaRpcClient;
import io.lifeengine.cryptobot.domain.oracle.OracleReading;
import io.lifeengine.cryptobot.domain.receipt.IntelligenceReceipt;
import io.lifeengine.cryptobot.domain.receipt.ReceiptInput;
import io.lifeengine.cryptobot.domain.transactions.ActionProposal;
import io.lifeengine.cryptobot.domain.transactions.ProposalStatus;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

/**
 * KAN-439 — the envelope's data-integrity assumptions at execution time: a fresh reading is taken
 * right before signing, the plan is re-checked against it, and the EXECUTION receipt names the
 * quotes the decision was priced with.
 */
class ExecutionServiceOracleTest {

    private final ExecutionHarness h = new ExecutionHarness();

    @Test
    @DisplayName("the oracle is re-read before signing; a refused reading is a 409, nothing is signed, the proposal stays APPROVED")
    void refusedReadingBlocksExecutionBeforeAnythingStarts() {
        OracleReading world = Fixtures.oracle("1000", h.now);
        when(h.oracle.read(any())).thenReturn(Mono.just(world));
        when(h.policy.oracleProblems(any(), eq(world))).thenReturn(List.of("Plan priced SOL at $100 but the oracle median is $1000 (9000 bps apart, limit 1000)"));

        assertThatThrownBy(() -> h.service.execute(h.wallet.ownerUserId(), h.approved.id(), "op").block())
                .isInstanceOf(ControlPlaneExceptions.Conflict.class)
                .hasMessageContaining("Oracle refused execution")
                .hasMessageContaining("9000 bps apart");

        verify(h.oracle).read(eq(List.of("SOL", "USDC")));
        verify(h.signer, never()).sign(any(), anyString(), anyString(), any(), any());
        verify(h.rpc, never()).sendTransaction(any(), anyString());
        assertThat(h.current().status()).isEqualTo(ProposalStatus.APPROVED);
        assertThat(h.current().operationId()).isNull();
        assertThat(h.auditTypes()).isEmpty();
        assertThat(h.count("oracle.execution.refused")).isEqualTo(1);
    }

    @Test
    @DisplayName("the world agreeing with the plan: execution proceeds and the receipt carries the oracle reading as an input")
    void acceptedReadingLetsTheTradeThroughAndTheReceiptNamesTheQuotes() {
        String sig = h.signerSignsForReal();
        when(h.rpc.sendTransaction(eq(SolanaCluster.DEVNET), anyString())).thenReturn(Mono.just(sig));
        when(h.rpc.getSignatureStatus(eq(SolanaCluster.DEVNET), eq(sig)))
                .thenReturn(Mono.just(new SolanaRpcClient.SignatureStatus(sig, "confirmed", false, null)));

        ActionProposal done = h.service.execute(h.wallet.ownerUserId(), h.approved.id(), "op").block();

        assertThat(done.status()).isEqualTo(ProposalStatus.EXECUTED);
        verify(h.oracle).read(eq(List.of("SOL", "USDC")));
        List<IntelligenceReceipt> receipts = h.receipts();
        assertThat(receipts).hasSize(1);
        IntelligenceReceipt receipt = receipts.get(0);
        assertThat(receipt.body().inputs()).extracting(ReceiptInput::type).contains(ReceiptInput.ORACLE_READING, ReceiptInput.TRANSACTION, ReceiptInput.APPROVAL);
        String expected = h.approved.policy().oracle().quotesHash();
        assertThat(receipt.body().inputs()).filteredOn(i -> i.type().equals(ReceiptInput.ORACLE_READING)).extracting(ReceiptInput::hash).containsExactly(expected);
        assertThat(h.count("oracle.execution.refused")).isZero(); // registered at 0, never incremented
    }
}
