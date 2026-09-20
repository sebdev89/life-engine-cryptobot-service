package io.lifeengine.cryptobot.application.controlplane;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.lifeengine.cryptobot.adapters.solana.SolanaCluster;
import io.lifeengine.cryptobot.adapters.solana.SolanaRpcClient;
import io.lifeengine.cryptobot.adapters.solana.SolanaRpcException;
import io.lifeengine.cryptobot.application.reliability.ReconciliationService;
import io.lifeengine.cryptobot.application.reliability.ReliabilityProperties;
import io.lifeengine.cryptobot.domain.reliability.TradeEvents;
import io.lifeengine.cryptobot.domain.transactions.ActionProposal;
import io.lifeengine.cryptobot.domain.transactions.ExecutionRecord;
import io.lifeengine.cryptobot.domain.transactions.ProposalStatus;
import io.lifeengine.cryptobot.testsupport.InMemoryControlPlaneRepositories;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

/**
 * KAN-403 — the two acceptance tests of the issue and the rules around them:
 * <ul>
 *   <li>double POST with the same {@code operationId} ⇒ one transaction;
 *   <li>crash between submit and confirm ⇒ reconciliation finishes the trade without re-sending.
 * </ul>
 */
class ExecutionServiceReliabilityTest {

    private final ExecutionHarness h = new ExecutionHarness();
    private final ReliabilityProperties props = new ReliabilityProperties(null,
            new ReliabilityProperties.Reconciliation(true, Duration.ofSeconds(30), Duration.ofMinutes(2), 3, 100, null));
    private final ReconciliationService reconciliation = new ReconciliationService(h.repo, InMemoryControlPlaneRepositories.deadLetters(),
            h.rpc, h.audit, h.metrics, props, h.executionReceipts, h.service);

    @Test
    @DisplayName("double POST with the same operationId: one sendTransaction, same result, duplicate counted")
    void samOperationIdIsIdempotent() {
        String sig = h.signerSignsForReal();
        when(h.rpc.sendTransaction(eq(SolanaCluster.DEVNET), anyString())).thenReturn(Mono.just(sig));
        when(h.rpc.getSignatureStatus(eq(SolanaCluster.DEVNET), eq(sig)))
                .thenReturn(Mono.just(new SolanaRpcClient.SignatureStatus(sig, "confirmed", false, null)));
        UUID op = UUID.randomUUID();

        ActionProposal first = h.service.execute(h.wallet.ownerUserId(), h.approved.id(), "op", op).block();
        ActionProposal second = h.service.execute(h.wallet.ownerUserId(), h.approved.id(), "op", op).block();

        assertThat(first.status()).isEqualTo(ProposalStatus.EXECUTED);
        assertThat(second.status()).isEqualTo(ProposalStatus.EXECUTED);
        assertThat(second.operationId()).isEqualTo(op);
        assertThat(second.execution().signature()).isEqualTo(first.execution().signature());
        verify(h.rpc, times(1)).sendTransaction(eq(SolanaCluster.DEVNET), anyString());
        assertThat(h.count("duplicate.trade.suppressed")).isEqualTo(1);
        assertThat(h.count("trade.submitted", "asset", "SOL")).isEqualTo(1);

        // KAN-391: one attempt ⇒ one EXECUTION receipt (nonce = the operation id), signed, with the chain's answer hashed in.
        assertThat(h.receipts()).hasSize(1);
        var receipt = h.receipts().get(0);
        assertThat(receipt.kind()).isEqualTo(io.lifeengine.cryptobot.domain.receipt.ReceiptKind.EXECUTION);
        assertThat(receipt.body().nonce()).isEqualTo("exec:" + op);
        assertThat(receipt.body().inputs()).extracting(io.lifeengine.cryptobot.domain.receipt.ReceiptInput::type)
                .contains(io.lifeengine.cryptobot.domain.receipt.ReceiptInput.TRANSACTION, io.lifeengine.cryptobot.domain.receipt.ReceiptInput.APPROVAL);
        assertThat(receipt.canonicalJson()).doesNotContain(sig); // the signature is hashed inside output.hash, never listed in clear
        assertThat(h.receiptService.verify(receipt).block().valid()).isTrue();
        assertThat(h.count("intelligence.receipts", "result", "issued")).isEqualTo(1);
    }

    @Test
    @DisplayName("the operationId is persisted with EXECUTING before anything is signed, and the audit trail shows the order")
    void operationIdIsBoundBeforeSigning() {
        String sig = h.signerSignsForReal();
        when(h.rpc.sendTransaction(eq(SolanaCluster.DEVNET), anyString())).thenReturn(Mono.just(sig));
        when(h.rpc.getSignatureStatus(eq(SolanaCluster.DEVNET), eq(sig)))
                .thenReturn(Mono.just(new SolanaRpcClient.SignatureStatus(sig, "finalized", false, null)));
        UUID op = UUID.randomUUID();

        ActionProposal done = h.service.execute(h.wallet.ownerUserId(), h.approved.id(), "op", op).block();

        assertThat(done.operationId()).isEqualTo(op);
        assertThat(done.version()).isEqualTo(4); // APPROVED→EXECUTING, +SIGNED, →SUBMITTED, →EXECUTED
        assertThat(done.execution().signature()).isEqualTo(sig);
        assertThat(done.execution().recentBlockhash()).isEqualTo("blockhash");
        assertThat(done.execution().lastValidBlockHeight()).isEqualTo(1000L);
        assertThat(h.auditTypes()).containsExactly(ExecutionService.EV_STARTED, ExecutionService.EV_VALIDATED, ExecutionService.EV_SIGNED, ExecutionService.EV_SUBMITTED, ExecutionService.EV_EXECUTED);
        assertThat(h.outboxTypes()).containsExactly(TradeEvents.SUBMITTED, TradeEvents.CONFIRMED);
    }

    @Test
    @DisplayName("a different operationId while the proposal is in flight is a 409, not a second transaction")
    void differentOperationIdWhileInFlightConflicts() {
        String sig = h.signerSignsForReal();
        when(h.rpc.sendTransaction(eq(SolanaCluster.DEVNET), anyString())).thenReturn(Mono.just(sig));
        // The chain never answers within the sync wait: the row stays SUBMITTED.
        when(h.rpc.getSignatureStatus(eq(SolanaCluster.DEVNET), eq(sig))).thenReturn(Mono.error(new TimeoutException("rpc timeout")));

        ActionProposal first = h.service.execute(h.wallet.ownerUserId(), h.approved.id(), "op", UUID.randomUUID()).block();
        assertThat(first.status()).isEqualTo(ProposalStatus.SUBMITTED);

        assertThatThrownBy(() -> h.service.execute(h.wallet.ownerUserId(), h.approved.id(), "op", UUID.randomUUID()).block())
                .isInstanceOf(ControlPlaneExceptions.Conflict.class)
                .hasMessageContaining("SUBMITTED");
        verify(h.rpc, times(1)).sendTransaction(eq(SolanaCluster.DEVNET), anyString());
    }

    @Test
    @DisplayName("crash between submit and confirm: the row is SUBMITTED, reconciliation finishes it, nothing is re-sent")
    void crashBetweenSubmitAndConfirmIsReconciled() {
        String sig = h.signerSignsForReal();
        when(h.rpc.sendTransaction(eq(SolanaCluster.DEVNET), anyString())).thenReturn(Mono.just(sig));
        when(h.rpc.getSignatureStatus(eq(SolanaCluster.DEVNET), eq(sig)))
                // 1st: the process "dies" while polling (the error is the last thing it sees)
                .thenReturn(Mono.error(new RuntimeException("process killed")))
                // 2nd: the reconciler, after restart, asks the chain
                .thenReturn(Mono.just(new SolanaRpcClient.SignatureStatus(sig, "confirmed", false, null)));
        UUID op = UUID.randomUUID();

        ActionProposal interrupted = h.service.execute(h.wallet.ownerUserId(), h.approved.id(), "op", op).block();
        assertThat(interrupted.status()).isEqualTo(ProposalStatus.SUBMITTED);
        assertThat(interrupted.execution().status()).isEqualTo(ExecutionRecord.SUBMITTED);
        assertThat(interrupted.execution().signature()).isEqualTo(sig);

        ReconciliationService.Result result = reconciliation.reconcile(h.current()).block();

        assertThat(result).isEqualTo(ReconciliationService.Result.CORRECTED);
        ActionProposal after = h.current();
        assertThat(after.status()).isEqualTo(ProposalStatus.EXECUTED);
        assertThat(after.execution().confirmationStatus()).isEqualTo("confirmed");
        assertThat(after.execution().reconciliationAttempts()).isEqualTo(1);
        verify(h.rpc, times(1)).sendTransaction(eq(SolanaCluster.DEVNET), anyString());
        assertThat(h.count("trade.reconciled", "result", "corrected")).isEqualTo(1);
        assertThat(h.count("trade.confirmed", "result", "confirmed", "asset", "SOL")).isEqualTo(1);
        assertThat(h.outboxTypes()).containsExactly(TradeEvents.SUBMITTED, TradeEvents.CONFIRMED);
        assertThat(h.auditTypes()).contains(ReconciliationService.EV_RECONCILED);

        // The client retries its click: same operationId ⇒ the reconciled result, no new transaction.
        ActionProposal replay = h.service.execute(h.wallet.ownerUserId(), h.approved.id(), "op", op).block();
        assertThat(replay.status()).isEqualTo(ProposalStatus.EXECUTED);
        verify(h.rpc, times(1)).sendTransaction(eq(SolanaCluster.DEVNET), anyString());

        // KAN-391: the reconciler left the EXECUTION receipt the interrupted path could not; the replay did not mint a second one.
        assertThat(h.receipts()).hasSize(1);
        assertThat(h.receipts().get(0).body().nonce()).isEqualTo("exec:" + op);
        assertThat(h.receipts().get(0).body().reproducibility()).isEqualTo(io.lifeengine.cryptobot.domain.receipt.ReproducibilityLevel.L0_SIGNED);
    }

    @Test
    @DisplayName("transport error on broadcast is uncertain: the row keeps its signature, stays EXECUTING, is never marked FAILED by guess")
    void transportErrorOnBroadcastIsNotAFailure() {
        String sig = h.signerSignsForReal();
        when(h.rpc.sendTransaction(eq(SolanaCluster.DEVNET), anyString()))
                .thenReturn(Mono.error(new SolanaRpcException("sendTransaction", -1, "Solana RPC call failed: timeout", null, new TimeoutException())));

        ActionProposal p = h.service.execute(h.wallet.ownerUserId(), h.approved.id(), "op", UUID.randomUUID()).block();

        assertThat(p.status()).isEqualTo(ProposalStatus.EXECUTING);
        assertThat(p.execution().status()).isEqualTo(ExecutionRecord.SIGNED);
        assertThat(p.execution().signature()).isEqualTo(sig);
        assertThat(h.auditTypes()).containsExactly(ExecutionService.EV_STARTED, ExecutionService.EV_VALIDATED, ExecutionService.EV_SIGNED, ExecutionService.EV_BROADCAST_UNCERTAIN);
        assertThat(h.registry.find("trade.failed").tag("asset", "SOL").counter()).isNull();

        // Later the chain says it did land: reconciliation corrects EXECUTING → EXECUTED.
        when(h.rpc.getSignatureStatus(eq(SolanaCluster.DEVNET), eq(sig)))
                .thenReturn(Mono.just(new SolanaRpcClient.SignatureStatus(sig, "finalized", false, null)));
        assertThat(reconciliation.reconcile(h.current()).block()).isEqualTo(ReconciliationService.Result.CORRECTED);
        assertThat(h.current().status()).isEqualTo(ProposalStatus.EXECUTED);
        verify(h.rpc, times(1)).sendTransaction(eq(SolanaCluster.DEVNET), anyString());
    }

    @Test
    @DisplayName("EXECUTING without a signature (crashed before signing) reconciles to FAILED: nothing reached the chain")
    void crashBeforeSigningFails() {
        ActionProposal executing = h.repo.commit(io.lifeengine.cryptobot.domain.transactions.ProposalTransition.from(h.approved,
                h.approved.withOperation(UUID.randomUUID(), h.now).withStatus(ProposalStatus.EXECUTING, h.now))).block();

        assertThat(reconciliation.reconcile(executing).block()).isEqualTo(ReconciliationService.Result.CORRECTED);

        ActionProposal after = h.current();
        assertThat(after.status()).isEqualTo(ProposalStatus.FAILED);
        assertThat(after.execution().error()).contains("before the transaction was signed");
        verify(h.rpc, never()).getSignatureStatus(any(), anyString());
        verify(h.rpc, never()).sendTransaction(any(), anyString());
        assertThat(h.outboxTypes()).containsExactly(TradeEvents.FAILED);
        // KAN-391: a refused execution is a decision too — it leaves a FAILED EXECUTION receipt.
        assertThat(h.receipts()).hasSize(1);
        assertThat(h.receipts().get(0).kind()).isEqualTo(io.lifeengine.cryptobot.domain.receipt.ReceiptKind.EXECUTION);
        assertThat(h.receipts().get(0).body().output().schema()).isEqualTo("execution/1");
    }

    @Test
    @DisplayName("SUBMITTED but never seen and blockhash expired, retries disabled: dead-lettered (retries_exhausted), counted as a mismatch, never re-sent")
    void submittedNeverSeenAndExpiredWithoutRetriesIsDeadLettered() {
        ReliabilityProperties noRetries = new ReliabilityProperties(null,
                new ReliabilityProperties.Reconciliation(true, Duration.ofSeconds(30), Duration.ofMinutes(2), 3, 100, 0));
        ReconciliationService strict = new ReconciliationService(h.repo, InMemoryControlPlaneRepositories.deadLetters(), h.rpc, h.audit, h.metrics, noRetries,
                h.executionReceipts, h.service);
        String sig = h.signerSignsForReal();
        when(h.rpc.sendTransaction(eq(SolanaCluster.DEVNET), anyString())).thenReturn(Mono.just(sig));
        when(h.rpc.getSignatureStatus(eq(SolanaCluster.DEVNET), eq(sig)))
                .thenReturn(Mono.error(new RuntimeException("process killed")))
                .thenReturn(Mono.just(new SolanaRpcClient.SignatureStatus(sig, null, false, null))); // not found
        when(h.rpc.getBlockHeight(SolanaCluster.DEVNET)).thenReturn(Mono.just(1001L)); // > lastValidBlockHeight 1000

        h.service.execute(h.wallet.ownerUserId(), h.approved.id(), "op", UUID.randomUUID()).block();
        assertThat(strict.reconcile(h.current()).block()).isEqualTo(ReconciliationService.Result.DEAD_LETTERED);

        ActionProposal after = h.current();
        assertThat(after.status()).isEqualTo(ProposalStatus.SUBMITTED); // the truth we have; a human decides (KAN-571)
        assertThat(after.execution().error()).contains("blockhash expired").contains("0 idempotent retries exhausted");
        assertThat(InMemoryControlPlaneRepositories.DEAD_LETTERS).hasSize(1);
        assertThat(InMemoryControlPlaneRepositories.DEAD_LETTERS.get(0).payload()).containsEntry("kind", ReconciliationService.KIND_RETRIES_EXHAUSTED);
        assertThat(h.count("reconciliation.mismatch")).isEqualTo(1);
        assertThat(h.count("cryptobot.dead.letter", "reason", "retries_exhausted")).isEqualTo(1);
        verify(h.rpc, times(1)).sendTransaction(eq(SolanaCluster.DEVNET), anyString());
    }

    @Test
    @DisplayName("not yet seen but inside the blockhash window: left in flight, attempt counted, matched")
    void pendingInsideWindowIsLeftAlone() {
        String sig = h.signerSignsForReal();
        when(h.rpc.sendTransaction(eq(SolanaCluster.DEVNET), anyString())).thenReturn(Mono.just(sig));
        when(h.rpc.getSignatureStatus(eq(SolanaCluster.DEVNET), eq(sig)))
                .thenReturn(Mono.error(new RuntimeException("process killed")))
                .thenReturn(Mono.just(new SolanaRpcClient.SignatureStatus(sig, null, false, null)));
        when(h.rpc.getBlockHeight(SolanaCluster.DEVNET)).thenReturn(Mono.just(900L));

        h.service.execute(h.wallet.ownerUserId(), h.approved.id(), "op", UUID.randomUUID()).block();
        assertThat(reconciliation.reconcile(h.current()).block()).isEqualTo(ReconciliationService.Result.MATCHED);

        ActionProposal after = h.current();
        assertThat(after.status()).isEqualTo(ProposalStatus.SUBMITTED);
        assertThat(after.execution().reconciliationAttempts()).isEqualTo(1);
        assertThat(h.count("trade.reconciled", "result", "matched")).isEqualTo(1);
    }

    @Test
    @DisplayName("no verdict after maxAttempts (RPC down): dead-lettered once, dlq_size=1, then skipped")
    void ambiguousGoesToDeadLetterQueue() {
        String sig = h.signerSignsForReal();
        when(h.rpc.sendTransaction(eq(SolanaCluster.DEVNET), anyString())).thenReturn(Mono.just(sig));
        when(h.rpc.getSignatureStatus(eq(SolanaCluster.DEVNET), eq(sig))).thenReturn(Mono.error(new SolanaRpcException("getSignatureStatuses", -1, "down", null, new TimeoutException())));

        h.service.execute(h.wallet.ownerUserId(), h.approved.id(), "op", UUID.randomUUID()).block();
        assertThat(h.current().status()).isEqualTo(ProposalStatus.SUBMITTED);

        assertThat(reconciliation.reconcile(h.current()).block()).isEqualTo(ReconciliationService.Result.MATCHED);
        assertThat(reconciliation.reconcile(h.current()).block()).isEqualTo(ReconciliationService.Result.MATCHED);
        assertThat(reconciliation.reconcile(h.current()).block()).isEqualTo(ReconciliationService.Result.DEAD_LETTERED);
        assertThat(reconciliation.reconcile(h.current()).block()).isEqualTo(ReconciliationService.Result.SKIPPED);

        assertThat(InMemoryControlPlaneRepositories.DEAD_LETTERS).hasSize(1);
        assertThat(InMemoryControlPlaneRepositories.DEAD_LETTERS.get(0).proposalId()).isEqualTo(h.approved.id());
        assertThat(h.registry.get("dlq.size").gauge().value()).isEqualTo(1);
        assertThat(h.current().status()).isEqualTo(ProposalStatus.SUBMITTED); // the truth we have; a human decides
        assertThat(h.auditTypes()).contains(ReconciliationService.EV_AMBIGUOUS);
        verify(h.rpc, times(1)).sendTransaction(eq(SolanaCluster.DEVNET), anyString());
    }

    @Test
    @DisplayName("the sweep skips rows younger than the grace period and picks them up once they age")
    void sweepRespectsGrace() {
        String sig = h.signerSignsForReal();
        when(h.rpc.sendTransaction(eq(SolanaCluster.DEVNET), anyString())).thenReturn(Mono.just(sig));
        when(h.rpc.getSignatureStatus(eq(SolanaCluster.DEVNET), eq(sig)))
                .thenReturn(Mono.error(new RuntimeException("process killed")))
                .thenReturn(Mono.just(new SolanaRpcClient.SignatureStatus(sig, "confirmed", false, null)));
        h.service.execute(h.wallet.ownerUserId(), h.approved.id(), "op", UUID.randomUUID()).block();

        // "now" is right after the request: inside the 2-minute grace ⇒ untouched.
        ReconciliationService young = new ReconciliationService(h.repo, InMemoryControlPlaneRepositories.deadLetters(), h.rpc, h.audit, h.metrics, props, h.executionReceipts,
                h.service, Clock.fixed(h.current().updatedAt().plusSeconds(10), ZoneOffset.UTC));
        assertThat(young.sweep().block()).isZero();
        assertThat(h.current().status()).isEqualTo(ProposalStatus.SUBMITTED);

        ReconciliationService old = new ReconciliationService(h.repo, InMemoryControlPlaneRepositories.deadLetters(), h.rpc, h.audit, h.metrics, props, h.executionReceipts,
                h.service, Clock.fixed(h.current().updatedAt().plus(Duration.ofMinutes(3)), ZoneOffset.UTC));
        assertThat(old.sweep().block()).isEqualTo(1);
        assertThat(h.current().status()).isEqualTo(ProposalStatus.EXECUTED);
    }

    @Test
    @DisplayName("an operationId already bound to another proposal is refused")
    void operationIdIsUniqueAcrossProposals() {
        UUID op = UUID.randomUUID();
        ActionProposal other = h.repo.insert(new ActionProposal(UUID.randomUUID(), h.wallet.id(), h.wallet.ownerUserId(), h.wallet.address(), "devnet",
                ProposalStatus.EXECUTED, "REBALANCE", "t", null, "op", null, h.approved.plan(), null, null, null, null, h.tx, null, null, null, null,
                h.now, h.now, h.now, op, 0)).block();
        assertThat(other.operationId()).isEqualTo(op);

        assertThatThrownBy(() -> h.service.execute(h.wallet.ownerUserId(), h.approved.id(), "op", op).block())
                .isInstanceOf(ControlPlaneExceptions.DuplicateOperation.class);
        assertThat(h.current().status()).isEqualTo(ProposalStatus.APPROVED);
        verify(h.rpc, never()).sendTransaction(any(), anyString());
    }
}
