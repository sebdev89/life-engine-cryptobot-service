package io.lifeengine.cryptobot.application.controlplane;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.lifeengine.cryptobot.adapters.solana.SolanaCluster;
import io.lifeengine.cryptobot.adapters.solana.SolanaRpcClient;
import io.lifeengine.cryptobot.adapters.solana.SolanaRpcException;
import io.lifeengine.cryptobot.application.reliability.DeadLetterService;
import io.lifeengine.cryptobot.application.reliability.ReconciliationService;
import io.lifeengine.cryptobot.application.reliability.ReliabilityProperties;
import io.lifeengine.cryptobot.domain.reliability.DeadLetter;
import io.lifeengine.cryptobot.domain.reliability.OutboxEvent;
import io.lifeengine.cryptobot.domain.reliability.TradeEvents;
import io.lifeengine.cryptobot.domain.transactions.ActionProposal;
import io.lifeengine.cryptobot.domain.transactions.AuditEvent;
import io.lifeengine.cryptobot.domain.transactions.ExecutionRecord;
import io.lifeengine.cryptobot.domain.transactions.ProposalStatus;
import io.lifeengine.cryptobot.testsupport.InMemoryControlPlaneRepositories;
import java.net.ConnectException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

/**
 * KAN-571 (HK-3) — reconciliation + recovery, visible: a signature the chain never saw whose
 * blockhash expired is retried <em>idempotently</em> (same operationId, new signature) up to N
 * times, then dead-lettered; a human resolves or requeues the letter by API (KAN-501) and the
 * proposal ends in a state the chain proves — never a second transaction.
 */
class ExecutionRecoveryTest {

    private final ExecutionHarness h = new ExecutionHarness();
    private final ReliabilityProperties props = new ReliabilityProperties(null,
            new ReliabilityProperties.Reconciliation(true, Duration.ofSeconds(30), Duration.ofMinutes(2), 3, 100, 1));
    private final ReconciliationService reconciliation = new ReconciliationService(h.repo, InMemoryControlPlaneRepositories.deadLetters(),
            h.rpc, h.audit, h.metrics, props, h.executionReceipts, h.service);
    private final DeadLetterService dlq = new DeadLetterService(InMemoryControlPlaneRepositories.deadLetters(), h.repo,
            InMemoryControlPlaneRepositories.outbox(), reconciliation, h.audit, h.metrics);

    private static final SolanaRpcClient.SignatureStatus NOT_FOUND = new SolanaRpcClient.SignatureStatus("x", null, false, null);

    private static SolanaRpcException rpcDown(String method) {
        return new SolanaRpcException(method, -1, "Solana RPC call failed: rpc down", null, new ConnectException("rpc down"));
    }

    private List<AuditEvent> audit() {
        return InMemoryControlPlaneRepositories.AUDIT.stream().filter(e -> h.approved.id().equals(e.proposalId())).toList();
    }

    private AuditEvent auditOf(String type) {
        return audit().stream().filter(e -> type.equals(e.eventType())).findFirst().orElseThrow(() -> new AssertionError("no audit " + type));
    }

    @Test
    @DisplayName("broadcast lost (uncertain), blockhash expired unseen: retried under the same operationId with a new signature, then EXECUTED — one tx on chain")
    void expiredUnseenSignatureIsRetriedIdempotently() {
        String sig2 = h.signerSignsAnyMessage();
        String sig1 = h.signatureOf(h.tx);
        // 1st broadcast: transport error (nothing on the chain). 2nd (the retry): accepted and confirmed.
        when(h.rpc.sendTransaction(eq(SolanaCluster.DEVNET), anyString()))
                .thenReturn(Mono.error(rpcDown("sendTransaction")))
                .thenReturn(Mono.just(sig2));
        when(h.rpc.getSignatureStatus(eq(SolanaCluster.DEVNET), eq(sig1))).thenReturn(Mono.just(NOT_FOUND));
        when(h.rpc.getSignatureStatus(eq(SolanaCluster.DEVNET), eq(sig2)))
                .thenReturn(Mono.just(new SolanaRpcClient.SignatureStatus(sig2, "confirmed", false, null)));
        when(h.rpc.getBlockHeight(SolanaCluster.DEVNET)).thenReturn(Mono.just(1001L)); // > tx.lastValidBlockHeight (1000)
        UUID op = UUID.randomUUID();

        ActionProposal uncertain = h.service.execute(h.wallet.ownerUserId(), h.approved.id(), "op", op).block();
        assertThat(uncertain.status()).isEqualTo(ProposalStatus.EXECUTING);
        assertThat(uncertain.execution().signature()).isEqualTo(sig1);
        assertThat(h.auditTypes()).endsWith(ExecutionService.EV_BROADCAST_UNCERTAIN);

        ReconciliationService.Result result = reconciliation.reconcile(h.current()).block();

        assertThat(result).isEqualTo(ReconciliationService.Result.RETRIED);
        ActionProposal after = h.current();
        assertThat(after.status()).isEqualTo(ProposalStatus.EXECUTED);
        assertThat(after.operationId()).as("same operation").isEqualTo(op);
        assertThat(after.execution().signature()).as("new signature").isEqualTo(sig2).isNotEqualTo(sig1);
        assertThat(after.execution().previousSignature()).isEqualTo(sig1);
        assertThat(after.execution().retries()).isEqualTo(1);
        assertThat(after.execution().recentBlockhash()).isEqualTo("blockhash-2");
        assertThat(after.execution().lastValidBlockHeight()).isEqualTo(2000L);
        // Two sends, two signatures, but only the second one ever reached the node: the first was proven dead before retrying.
        verify(h.rpc, times(2)).sendTransaction(eq(SolanaCluster.DEVNET), anyString());
        assertThat(h.auditTypes()).containsSubsequence(ExecutionService.EV_STARTED, ExecutionService.EV_SIGNED, ExecutionService.EV_BROADCAST_UNCERTAIN,
                ExecutionService.EV_RETRIED, ExecutionService.EV_VALIDATED, ExecutionService.EV_SIGNED, ExecutionService.EV_SUBMITTED, ExecutionService.EV_EXECUTED);
        Map<String, Object> retried = auditOf(ExecutionService.EV_RETRIED).payload();
        assertThat(retried).containsEntry("previousSignature", sig1).containsEntry("signature", sig2).containsEntry("retry", "1").containsEntry("operationId", op.toString());
        assertThat(h.count("cryptobot.reconciliation", "outcome", "retried")).isEqualTo(1);
        assertThat(InMemoryControlPlaneRepositories.DEAD_LETTERS).isEmpty();
        // One EXECUTION receipt, nonce = the one operation.
        assertThat(h.receipts()).hasSize(1);
        assertThat(h.receipts().get(0).body().nonce()).isEqualTo("exec:" + op);
        // A replay of the client's click after all that: same row, no third send.
        ActionProposal replay = h.service.execute(h.wallet.ownerUserId(), h.approved.id(), "op", op).block();
        assertThat(replay.status()).isEqualTo(ProposalStatus.EXECUTED);
        verify(h.rpc, times(2)).sendTransaction(eq(SolanaCluster.DEVNET), anyString());
    }

    @Test
    @DisplayName("retries exhausted: dead letter retries_exhausted with the reason, row left in flight, sweep skips it; resolve closes it FAILED once the chain proves nothing landed")
    void exhaustedRetriesGoToDeadLetterAndResolveClosesIt() {
        String sig2 = h.signerSignsAnyMessage();
        String sig1 = h.signatureOf(h.tx);
        when(h.rpc.sendTransaction(eq(SolanaCluster.DEVNET), anyString())).thenReturn(Mono.error(rpcDown("sendTransaction")));
        when(h.rpc.getSignatureStatus(eq(SolanaCluster.DEVNET), anyString())).thenReturn(Mono.just(NOT_FOUND));
        when(h.rpc.getBlockHeight(SolanaCluster.DEVNET)).thenReturn(Mono.just(9_999L)); // every blockhash expired
        UUID op = UUID.randomUUID();
        h.service.execute(h.wallet.ownerUserId(), h.approved.id(), "op", op).block();

        assertThat(reconciliation.reconcile(h.current()).block()).isEqualTo(ReconciliationService.Result.RETRIED); // retry 1 of 1 — also lost
        assertThat(h.current().execution().signature()).isEqualTo(sig2);
        assertThat(h.current().execution().retries()).isEqualTo(1);
        assertThat(reconciliation.reconcile(h.current()).block()).isEqualTo(ReconciliationService.Result.DEAD_LETTERED);
        assertThat(reconciliation.reconcile(h.current()).block()).isEqualTo(ReconciliationService.Result.SKIPPED);

        ActionProposal parked = h.current();
        assertThat(parked.status()).isEqualTo(ProposalStatus.EXECUTING);
        assertThat(parked.operationId()).isEqualTo(op);
        assertThat(parked.execution().reconciliationAttempts()).isEqualTo(3);
        DeadLetter letter = InMemoryControlPlaneRepositories.DEAD_LETTERS.get(0);
        assertThat(InMemoryControlPlaneRepositories.DEAD_LETTERS).hasSize(1);
        assertThat(letter.source()).isEqualTo(DeadLetter.Source.RECONCILIATION);
        assertThat(letter.reason()).contains("1 idempotent retry exhausted (max 1)").contains(op.toString());
        assertThat(letter.payload()).containsEntry("kind", "retries_exhausted").containsEntry("signature", sig2).containsEntry("previousSignature", sig1).containsEntry("retries", "1");
        assertThat(letter.resolved()).isFalse();
        assertThat(h.auditTypes()).contains(ReconciliationService.EV_RETRIES_EXHAUSTED);
        assertThat(h.count("cryptobot.dead.letter", "reason", "retries_exhausted")).isEqualTo(1);
        assertThat(h.count("cryptobot.reconciliation", "outcome", "dead_lettered")).isEqualTo(1);
        assertThat(h.registry.get("cryptobot.dead.letter.open").gauge().value()).isEqualTo(1);

        // The human checks the explorer, sees nothing, and resolves: the chain still proves nothing landed ⇒ FAILED, letter closed.
        DeadLetterService.Resolution r = dlq.resolve(letter.id(), "ops@demo", "checked explorer: neither signature exists").block();

        assertThat(r.deadLetter().resolved()).isTrue();
        assertThat(r.deadLetter().resolvedBy()).isEqualTo("ops@demo");
        assertThat(r.deadLetter().resolution()).contains("checked explorer");
        assertThat(r.deadLetter().outcome()).isEqualTo(DeadLetter.Outcome.RESOLVED);
        assertThat(r.reconciliation()).isEqualTo(ReconciliationService.Result.CORRECTED);
        assertThat(r.proposal().status()).isEqualTo(ProposalStatus.FAILED);
        assertThat(h.current().status()).isEqualTo(ProposalStatus.FAILED);
        assertThat(h.current().execution().error()).contains("Closed by ops@demo").contains("checked explorer");
        assertThat(h.auditTypes()).contains(DeadLetterService.EV_RESOLVED);
        assertThat(auditOf(DeadLetterService.EV_RESOLVED).payload()).containsEntry("deadLetterId", letter.id().toString()).containsEntry("outcome", "RESOLVED");
        assertThat(h.outboxTypes()).containsExactly(TradeEvents.FAILED);
        assertThat(h.count("cryptobot.dead.letter", "reason", "resolved")).isEqualTo(1);
        assertThat(h.registry.get("cryptobot.dead.letter.open").gauge().value()).isZero();
        assertThat(h.registry.get("dlq.size").gauge().value()).isZero();
        // Resolving twice is a 409; nothing else moves.
        assertThatThrownBy(() -> dlq.resolve(letter.id(), "ops@demo", "again").block())
                .isInstanceOf(ControlPlaneExceptions.Conflict.class).hasMessageContaining("already resolved");
        verify(h.rpc, times(2)).sendTransaction(eq(SolanaCluster.DEVNET), anyString());
    }

    @Test
    @DisplayName("RPC down: no verdict ⇒ dead letter ambiguous; requeue resets the counter, reconciles now, retries idempotently, EXECUTED; a second requeue is a 409")
    void rpcDownAmbiguousThenRequeueRetriesOnce() {
        String sig2 = h.signerSignsAnyMessage();
        String sig1 = h.signatureOf(h.tx);
        when(h.rpc.sendTransaction(eq(SolanaCluster.DEVNET), anyString()))
                .thenReturn(Mono.error(rpcDown("sendTransaction")))
                .thenReturn(Mono.just(sig2));
        // The RPC stays down for the three reconciliation attempts...
        when(h.rpc.getSignatureStatus(eq(SolanaCluster.DEVNET), eq(sig1)))
                .thenReturn(Mono.error(rpcDown("getSignatureStatuses")))
                .thenReturn(Mono.error(rpcDown("getSignatureStatuses")))
                .thenReturn(Mono.error(rpcDown("getSignatureStatuses")))
                // ...and is back when the human requeues: the first signature was never seen and its blockhash has expired.
                .thenReturn(Mono.just(NOT_FOUND));
        when(h.rpc.getSignatureStatus(eq(SolanaCluster.DEVNET), eq(sig2)))
                .thenReturn(Mono.just(new SolanaRpcClient.SignatureStatus(sig2, "finalized", false, null)));
        when(h.rpc.getBlockHeight(SolanaCluster.DEVNET)).thenReturn(Mono.just(1500L));
        UUID op = UUID.randomUUID();
        h.service.execute(h.wallet.ownerUserId(), h.approved.id(), "op", op).block();

        assertThat(reconciliation.reconcile(h.current()).block()).isEqualTo(ReconciliationService.Result.MATCHED);
        assertThat(reconciliation.reconcile(h.current()).block()).isEqualTo(ReconciliationService.Result.MATCHED);
        assertThat(reconciliation.reconcile(h.current()).block()).isEqualTo(ReconciliationService.Result.DEAD_LETTERED);
        DeadLetter letter = InMemoryControlPlaneRepositories.DEAD_LETTERS.get(0);
        assertThat(letter.payload()).containsEntry("kind", "ambiguous");
        assertThat(letter.reason()).contains("No verdict after 3 reconciliation attempts").contains("RPC unavailable");
        assertThat(h.count("cryptobot.dead.letter", "reason", "ambiguous")).isEqualTo(1);
        // Resolve while the RPC is still down would be refused (no verdict) — the letter stays open. Not exercised here: the stub is back up.

        DeadLetterService.Resolution r = dlq.requeue(letter.id(), "ops@demo", "rpc is back, try again").block();

        assertThat(r.deadLetter().outcome()).isEqualTo(DeadLetter.Outcome.REQUEUED);
        assertThat(r.deadLetter().resolvedBy()).isEqualTo("ops@demo");
        assertThat(r.reconciliation()).isEqualTo(ReconciliationService.Result.RETRIED);
        assertThat(r.proposal().status()).isEqualTo(ProposalStatus.EXECUTED);
        ActionProposal after = h.current();
        assertThat(after.status()).isEqualTo(ProposalStatus.EXECUTED);
        assertThat(after.operationId()).isEqualTo(op);
        assertThat(after.execution().signature()).isEqualTo(sig2);
        assertThat(after.execution().previousSignature()).isEqualTo(sig1);
        assertThat(after.execution().confirmationStatus()).isEqualTo("finalized");
        assertThat(h.auditTypes()).containsSubsequence(ReconciliationService.EV_AMBIGUOUS, DeadLetterService.EV_REQUEUED, ExecutionService.EV_RETRIED, ExecutionService.EV_EXECUTED);
        assertThat(auditOf(DeadLetterService.EV_REQUEUED).payload()).containsEntry("deadLetterId", letter.id().toString()).containsEntry("note", "rpc is back, try again");
        assertThat(h.count("cryptobot.dead.letter", "reason", "requeued")).isEqualTo(1);
        assertThat(h.registry.get("cryptobot.dead.letter.open").gauge().value()).isZero();
        verify(h.rpc, times(2)).sendTransaction(eq(SolanaCluster.DEVNET), anyString());

        // Requeue again (double click, second operator): 409, and still exactly two sends.
        assertThatThrownBy(() -> dlq.requeue(letter.id(), "ops@demo", "again").block())
                .isInstanceOf(ControlPlaneExceptions.Conflict.class).hasMessageContaining("already resolved");
        verify(h.rpc, times(2)).sendTransaction(eq(SolanaCluster.DEVNET), anyString());
        assertThat(h.receipts()).hasSize(1);
    }

    @Test
    @DisplayName("resolve with no verdict yet (inside the blockhash window or RPC down) is a 409 and the letter stays open; requeue instead keeps it in the sweep")
    void resolveWithoutVerdictIsRefused() {
        h.signerSignsAnyMessage();
        String sig1 = h.signatureOf(h.tx);
        when(h.rpc.sendTransaction(eq(SolanaCluster.DEVNET), anyString())).thenReturn(Mono.error(rpcDown("sendTransaction")));
        when(h.rpc.getSignatureStatus(eq(SolanaCluster.DEVNET), eq(sig1))).thenReturn(Mono.error(rpcDown("getSignatureStatuses")));
        h.service.execute(h.wallet.ownerUserId(), h.approved.id(), "op", UUID.randomUUID()).block();
        for (int i = 0; i < 3; i++) {
            reconciliation.reconcile(h.current()).block();
        }
        DeadLetter letter = InMemoryControlPlaneRepositories.DEAD_LETTERS.get(0);

        assertThatThrownBy(() -> dlq.resolve(letter.id(), "ops@demo", "close it").block())
                .isInstanceOf(ControlPlaneExceptions.Conflict.class).hasMessageContaining("no verdict from the chain yet");
        assertThat(InMemoryControlPlaneRepositories.DEAD_LETTERS.get(0).resolved()).isFalse();
        assertThat(h.current().status()).isEqualTo(ProposalStatus.EXECUTING);

        // Requeue: the RPC is still down ⇒ no verdict, MATCHED (attempt 1 of 3 again), letter closed as REQUEUED, row back in the sweep.
        DeadLetterService.Resolution r = dlq.requeue(letter.id(), "ops@demo", null).block();
        assertThat(r.reconciliation()).isEqualTo(ReconciliationService.Result.MATCHED);
        assertThat(r.proposal().execution().reconciliationAttempts()).isEqualTo(1);
        assertThat(r.deadLetter().outcome()).isEqualTo(DeadLetter.Outcome.REQUEUED);
        verify(h.rpc, times(1)).sendTransaction(eq(SolanaCluster.DEVNET), anyString());
    }

    @Test
    @DisplayName("resolve when the chain says confirmed: the proposal is closed EXECUTED by the human's settle, with the RECONCILED audit and the receipt")
    void resolveAppliesTheChainsVerdict() {
        String sig = h.signerSignsForReal();
        when(h.rpc.sendTransaction(eq(SolanaCluster.DEVNET), anyString())).thenReturn(Mono.just(sig));
        when(h.rpc.getSignatureStatus(eq(SolanaCluster.DEVNET), eq(sig)))
                .thenReturn(Mono.error(rpcDown("getSignatureStatuses"))) // confirm loop interrupted ⇒ SUBMITTED
                .thenReturn(Mono.error(rpcDown("getSignatureStatuses")))
                .thenReturn(Mono.error(rpcDown("getSignatureStatuses")))
                .thenReturn(Mono.error(rpcDown("getSignatureStatuses"))) // 3 attempts ⇒ DLQ
                .thenReturn(Mono.just(new SolanaRpcClient.SignatureStatus(sig, "confirmed", false, null))); // the human's settle
        h.service.execute(h.wallet.ownerUserId(), h.approved.id(), "op", UUID.randomUUID()).block();
        assertThat(h.current().status()).isEqualTo(ProposalStatus.SUBMITTED);
        for (int i = 0; i < 3; i++) {
            reconciliation.reconcile(h.current()).block();
        }
        DeadLetter letter = InMemoryControlPlaneRepositories.DEAD_LETTERS.get(0);

        DeadLetterService.Resolution r = dlq.resolve(letter.id(), "ops@demo", "explorer shows it confirmed").block();

        assertThat(r.reconciliation()).isEqualTo(ReconciliationService.Result.CORRECTED);
        assertThat(h.current().status()).isEqualTo(ProposalStatus.EXECUTED);
        assertThat(h.current().execution().signature()).isEqualTo(sig);
        assertThat(h.auditTypes()).containsSubsequence(ReconciliationService.EV_AMBIGUOUS, ReconciliationService.EV_RECONCILED, ExecutionService.EV_EXECUTED, DeadLetterService.EV_RESOLVED);
        assertThat(auditOf(ReconciliationService.EV_RECONCILED).actor()).isEqualTo("ops@demo");
        assertThat(h.receipts()).hasSize(1);
        verify(h.rpc, times(1)).sendTransaction(eq(SolanaCluster.DEVNET), anyString());
    }

    @Test
    @DisplayName("an OUTBOX dead letter: requeue puts the event back to PENDING with attempts reset; resolve just closes the letter")
    void outboxLettersRequeueAndResolve() {
        Instant now = Instant.parse("2026-09-20T10:00:00Z");
        OutboxEvent failed = OutboxEvent.pending(OutboxEvent.AGGREGATE_PROPOSAL, h.approved.id(), h.wallet.ownerUserId(), TradeEvents.SUBMITTED, Map.of(), now).failed("sink down");
        InMemoryControlPlaneRepositories.OUTBOX.put(failed.id(), failed);
        DeadLetter a = DeadLetter.of(DeadLetter.Source.OUTBOX, failed.id(), h.approved.id(), h.wallet.ownerUserId(), "Outbox delivery failed after 8 attempts", Map.of("kind", "outbox"), now);
        OutboxEvent failed2 = OutboxEvent.pending(OutboxEvent.AGGREGATE_PROPOSAL, h.approved.id(), h.wallet.ownerUserId(), TradeEvents.CONFIRMED, Map.of(), now).failed("sink down");
        InMemoryControlPlaneRepositories.OUTBOX.put(failed2.id(), failed2);
        DeadLetter b = DeadLetter.of(DeadLetter.Source.OUTBOX, failed2.id(), h.approved.id(), h.wallet.ownerUserId(), "Outbox delivery failed after 8 attempts", Map.of("kind", "outbox"), now.plusSeconds(1));
        InMemoryControlPlaneRepositories.DEAD_LETTERS.add(a);
        InMemoryControlPlaneRepositories.DEAD_LETTERS.add(b);

        assertThat(dlq.list(false, null, null, 50, 0).collectList().block()).extracting(DeadLetter::id).containsExactly(b.id(), a.id()); // newest first
        assertThat(dlq.list(null, DeadLetter.Source.RECONCILIATION, null, 50, 0).collectList().block()).isEmpty();

        DeadLetterService.Resolution requeued = dlq.requeue(a.id(), "ops@demo", "sink is back").block();
        assertThat(requeued.event().status()).isEqualTo(OutboxEvent.Status.PENDING);
        assertThat(requeued.event().attempts()).isZero();
        assertThat(requeued.event().lastError()).isNull();
        assertThat(InMemoryControlPlaneRepositories.OUTBOX.get(failed.id()).status()).isEqualTo(OutboxEvent.Status.PENDING);
        assertThat(requeued.deadLetter().outcome()).isEqualTo(DeadLetter.Outcome.REQUEUED);
        assertThatThrownBy(() -> dlq.requeue(a.id(), "ops@demo", null).block()).isInstanceOf(ControlPlaneExceptions.Conflict.class);

        DeadLetterService.Resolution resolved = dlq.resolve(b.id(), "ops@demo", "consumer does not need it").block();
        assertThat(resolved.deadLetter().outcome()).isEqualTo(DeadLetter.Outcome.RESOLVED);
        assertThat(InMemoryControlPlaneRepositories.OUTBOX.get(failed2.id()).status()).isEqualTo(OutboxEvent.Status.FAILED);
        assertThat(dlq.open().block()).isZero();
        assertThat(dlq.list(true, null, null, 50, 0).collectList().block()).hasSize(2);
        assertThat(h.auditTypes()).containsExactly(DeadLetterService.EV_REQUEUED, DeadLetterService.EV_RESOLVED);
        assertThatThrownBy(() -> dlq.resolve(UUID.randomUUID(), "ops@demo", null).block()).isInstanceOf(ControlPlaneExceptions.NotFound.class);
    }

    @Test
    @DisplayName("a live request that moved the row first wins: the reconciler's retry is refused by the version guard, never a second send")
    void retryIsGuardedByTheVersion() {
        h.signerSignsAnyMessage();
        String sig1 = h.signatureOf(h.tx);
        when(h.rpc.sendTransaction(eq(SolanaCluster.DEVNET), anyString())).thenReturn(Mono.error(rpcDown("sendTransaction")));
        when(h.rpc.getSignatureStatus(eq(SolanaCluster.DEVNET), eq(sig1))).thenReturn(Mono.just(NOT_FOUND));
        when(h.rpc.getBlockHeight(SolanaCluster.DEVNET)).thenReturn(Mono.just(1001L));
        h.service.execute(h.wallet.ownerUserId(), h.approved.id(), "op", UUID.randomUUID()).block();
        ActionProposal stale = h.current();
        // Somebody else (the human's resolve, say) closed the row between the read and the retry.
        h.repo.commit(io.lifeengine.cryptobot.domain.transactions.ProposalTransition.from(stale,
                stale.withExecution(stale.execution().withStatus(ExecutionRecord.FAILED, null, null, "closed"), Instant.now()).withStatus(ProposalStatus.FAILED, Instant.now()))).block();

        assertThatThrownBy(() -> reconciliation.reconcile(stale).block()).isInstanceOf(ControlPlaneExceptions.StaleProposal.class);
        verify(h.rpc, times(1)).sendTransaction(eq(SolanaCluster.DEVNET), anyString());
        assertThat(h.current().status()).isEqualTo(ProposalStatus.FAILED);
    }
}
