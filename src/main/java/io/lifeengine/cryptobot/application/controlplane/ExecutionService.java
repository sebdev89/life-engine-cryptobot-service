package io.lifeengine.cryptobot.application.controlplane;

import io.lifeengine.cryptobot.adapters.solana.Base58;
import io.lifeengine.cryptobot.adapters.solana.SolanaCluster;
import io.lifeengine.cryptobot.adapters.solana.SolanaRpcClient;
import io.lifeengine.cryptobot.adapters.solana.SolanaRpcException;
import io.lifeengine.cryptobot.adapters.solana.tx.SolanaKeypair;
import io.lifeengine.cryptobot.domain.reliability.TradeEvents;
import io.lifeengine.cryptobot.domain.transactions.ActionProposal;
import io.lifeengine.cryptobot.domain.transactions.ExecutionRecord;
import io.lifeengine.cryptobot.domain.transactions.PreparedTransaction;
import io.lifeengine.cryptobot.domain.transactions.ProposalStatus;
import io.lifeengine.cryptobot.domain.transactions.ProposalTransition;
import io.lifeengine.cryptobot.domain.wallet.Wallet;
import io.lifeengine.cryptobot.integration.signer.SignerClient;
import io.lifeengine.cryptobot.observability.CryptobotMetrics;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

/**
 * The only path to the chain. Requires an APPROVED proposal, re-checks policy, rebuilds the
 * transaction on a fresh blockhash, re-simulates, asks the isolated signer, verifies the
 * signature against the wallet's public key, broadcasts, and waits for confirmation.
 *
 * <h2>Reliability (KAN-403)</h2>
 *
 * <ul>
 *   <li><b>Idempotent submit.</b> The caller's {@code operationId} is bound to the proposal in the
 *       same transaction that moves it to {@code EXECUTING}, guarded by the optimistic version: of
 *       two concurrent clicks exactly one wins; the other gets the winner's row back, never a
 *       second transaction. The same id replayed later returns the same result.
 *   <li><b>Signature before broadcast.</b> The signature is derived from the signed bytes and
 *       persisted ({@code SIGNED}) before {@code sendTransaction}. A crash at any later point
 *       leaves a row the {@code ReconciliationService} can look up on the chain.
 *   <li><b>No FAILED on doubt.</b> Only a node-side rejection fails a signed transaction. A
 *       transport error or timeout on broadcast, or any error while polling for confirmation,
 *       leaves the row in flight for reconciliation — a retry here would be the double trade
 *       Solana only protects against while the blockhash lives (~90 s).
 * </ul>
 */
@Service
public class ExecutionService {

    private static final Logger log = LoggerFactory.getLogger(ExecutionService.class);
    public static final String EV_STARTED = "EXECUTION_STARTED";
    public static final String EV_SIGNED = "EXECUTION_SIGNED";
    public static final String EV_SUBMITTED = "EXECUTION_SUBMITTED";
    public static final String EV_BROADCAST_UNCERTAIN = "EXECUTION_BROADCAST_UNCERTAIN";
    public static final String EV_CONFIRMATION_PENDING = "EXECUTION_CONFIRMATION_PENDING";
    public static final String EV_DUPLICATE_SUPPRESSED = "EXECUTION_DUPLICATE_SUPPRESSED";
    public static final String EV_EXECUTED = "EXECUTED";
    public static final String EV_FAILED = "EXECUTION_FAILED";

    private final ProposalService proposals;
    private final WalletService wallets;
    private final SimulationService simulation;
    private final PolicyEngine policy;
    private final SignerClient signer;
    private final SolanaRpcClient rpc;
    private final AuditService audit;
    private final CryptobotMetrics metrics;
    private final ExecutionReceipts executionReceipts;
    private final Clock clock;

    public ExecutionService(ProposalService proposals, WalletService wallets, SimulationService simulation, PolicyEngine policy,
            SignerClient signer, SolanaRpcClient rpc, AuditService audit, CryptobotMetrics metrics, ExecutionReceipts executionReceipts) {
        this.proposals = proposals;
        this.wallets = wallets;
        this.simulation = simulation;
        this.policy = policy;
        this.signer = signer;
        this.rpc = rpc;
        this.audit = audit;
        this.metrics = metrics;
        this.executionReceipts = executionReceipts;
        this.clock = Clock.systemUTC();
    }

    /** Server-generated operation id: still exactly one transaction, but the caller cannot replay it. */
    public Mono<ActionProposal> execute(UUID ownerUserId, UUID proposalId, String actor) {
        return execute(ownerUserId, proposalId, actor, UUID.randomUUID());
    }

    /**
     * @param operationId the idempotency key. Same id ⇒ same result and no new transaction; a
     *     different id while the proposal is in flight ⇒ 409.
     */
    public Mono<ActionProposal> execute(UUID ownerUserId, UUID proposalId, String actor, UUID operationId) {
        return proposals.require(ownerUserId, proposalId).flatMap(p -> {
            if (operationId.equals(p.operationId())) {
                return suppressDuplicate(p, actor);
            }
            if (p.status().inFlight()) {
                return Mono.error(new ControlPlaneExceptions.Conflict("Proposal is " + p.status() + " under operation " + p.operationId()
                        + "; retry with the same operationId or wait for the result"));
            }
            List<String> problems = policy.executionPreconditions(p);
            if (!problems.isEmpty()) {
                return Mono.error(new ControlPlaneExceptions.Conflict(String.join("; ", problems)));
            }
            return wallets.require(ownerUserId, p.walletId())
                    .flatMap(wallet -> start(p, operationId, actor)
                            .flatMap(executing -> executing.operationId().equals(operationId) && executing.status() == ProposalStatus.EXECUTING
                                    && executing.execution() == null
                                    ? run(executing, wallet, actor)
                                    : Mono.just(executing)));
        });
    }

    private Mono<ActionProposal> suppressDuplicate(ActionProposal p, String actor) {
        metrics.duplicateTradeSuppressed();
        log.info("execution_duplicate_suppressed proposalId={} operationId={} status={}", p.id(), p.operationId(), p.status());
        return Mono.just(p);
    }

    /**
     * APPROVED → EXECUTING with the operation id, one guarded commit. If the guard fails, someone
     * else moved the row: re-read, and if it is our own operation (the same click arrived twice at
     * once) hand the winner's row back instead of failing.
     */
    private Mono<ActionProposal> start(ActionProposal p, UUID operationId, String actor) {
        Instant now = clock.instant();
        ActionProposal executing = p.withOperation(operationId, now).withStatus(ProposalStatus.EXECUTING, now);
        return proposals.commit(ProposalTransition.from(p, executing)
                        .audit(audit.event(p.ownerUserId(), p.walletId(), p.id(), EV_STARTED, actor, ProposalService.payload("operationId", operationId))))
                .onErrorResume(ControlPlaneExceptions.StaleProposal.class, stale -> proposals.require(p.ownerUserId(), p.id())
                        .flatMap(fresh -> operationId.equals(fresh.operationId()) ? suppressDuplicate(fresh, actor) : Mono.error(stale)));
    }

    private record Signed(PreparedTransaction tx, String signedBase64, String signer, String signature) {}

    private record Step(ActionProposal proposal, Signed signed) {}

    private Mono<ActionProposal> run(ActionProposal executing, Wallet wallet, String actor) {
        long lamports = executing.transaction().lamports();
        String asset = ProposalService.assetOf(executing.plan());
        // Which step of the pipeline we are in, so a failure is counted where it happened
        // ("dónde se cae"), not just as "failed".
        AtomicReference<CryptobotMetrics.FailureStage> stage = new AtomicReference<>(CryptobotMetrics.FailureStage.PREFLIGHT);
        // 1. Fresh blockhash: the one from approval time is almost certainly expired.
        return simulation.prepareTransfer(wallet, lamports)
                // 2. Re-simulate the exact bytes that will be signed.
                .flatMap(tx -> rpc.simulateTransaction(wallet.cluster(), tx.unsignedTransactionBase64(), false)
                        .flatMap(sim -> sim.ok() ? Mono.just(tx)
                                : Mono.error(new ControlPlaneExceptions.Conflict("Pre-flight simulation failed: " + sim.error()))))
                // 3. Sign in the isolated signer, then verify the signature ourselves.
                .doOnNext(tx -> stage.set(CryptobotMetrics.FailureStage.SIGN))
                .flatMap(tx -> signer.sign(executing.id(), tx.unsignedTransactionBase64(), wallet.address())
                        .map(resp -> verifySigned(tx, resp, wallet)))
                // 4. Persist the signature BEFORE broadcasting: from here on a crash is reconcilable.
                .flatMap(signed -> persistSigned(executing, signed, wallet, actor).map(s -> new Step(s, signed)))
                // Anything up to here failed before the chain could have seen the transaction: safe to FAILED.
                .onErrorResume(ex -> fail(executing, actor, ex, stage.get(), asset).map(p -> new Step(p, null)))
                .flatMap(step -> step.signed() == null ? Mono.just(step.proposal()) : broadcast(step.proposal(), step.signed(), wallet, actor, asset));
    }

    /** Defence in depth: the signer's output must be the same message we sent, signed by the wallet key. */
    private static Signed verifySigned(PreparedTransaction tx, SignerClient.SignResponse resp, Wallet wallet) {
        byte[] wire = Base64.getDecoder().decode(resp.signedTransactionBase64());
        byte[] message = Base64.getDecoder().decode(tx.messageBase64());
        // 1 signature: compact-u16 (1 byte) + 64 bytes, then the message.
        if (wire.length != 1 + 64 + message.length || wire[0] != 1) {
            throw new ControlPlaneExceptions.Conflict("Signer returned a transaction with an unexpected shape");
        }
        byte[] signature = Arrays.copyOfRange(wire, 1, 65);
        byte[] returnedMessage = Arrays.copyOfRange(wire, 65, wire.length);
        if (!Arrays.equals(message, returnedMessage)) {
            throw new ControlPlaneExceptions.Conflict("Signer altered the transaction message");
        }
        if (!SolanaKeypair.verify(Base58.decode(wallet.address()), message, signature)) {
            throw new ControlPlaneExceptions.Conflict("Signature does not verify against the wallet public key");
        }
        // The transaction id IS the first signature: known before anyone broadcasts it.
        return new Signed(tx, resp.signedTransactionBase64(), resp.signer(), Base58.encode(signature));
    }

    private Mono<ActionProposal> persistSigned(ActionProposal executing, Signed signed, Wallet wallet, String actor) {
        Instant now = clock.instant();
        ExecutionRecord rec = new ExecutionRecord(ExecutionRecord.SIGNED, signed.signature(), wallet.cluster().explorerTxUrl(signed.signature()),
                signed.signer(), null, null, null, null, signed.tx().recentBlockhash(), signed.tx().lastValidBlockHeight(), 0, null);
        return proposals.commit(ProposalTransition.from(executing, executing.withExecution(rec, now))
                .audit(audit.event(executing.ownerUserId(), executing.walletId(), executing.id(), EV_SIGNED, actor,
                        ProposalService.payload("signature", signed.signature(), "signer", signed.signer(), "blockhash", signed.tx().recentBlockhash(),
                                "lastValidBlockHeight", signed.tx().lastValidBlockHeight()))));
    }

    private Mono<ActionProposal> broadcast(ActionProposal signedP, Signed signed, Wallet wallet, String actor, String asset) {
        long lamports = signedP.transaction().lamports();
        return rpc.sendTransaction(wallet.cluster(), signed.signedBase64())
                .flatMap(sig -> {
                    if (!sig.equals(signed.signature())) {
                        log.warn("execution_signature_mismatch proposalId={} expected={} returned={}", signedP.id(), signed.signature(), sig);
                    }
                    Instant submitted = clock.instant();
                    // Funnel step 3: the chain has the transaction.
                    metrics.tradeSubmitted(asset);
                    ActionProposal next = signedP.withExecution(signedP.execution().withSubmitted(submitted), submitted).withStatus(ProposalStatus.SUBMITTED, submitted);
                    return proposals.commit(ProposalTransition.from(signedP, next)
                            .audit(audit.event(signedP.ownerUserId(), signedP.walletId(), signedP.id(), EV_SUBMITTED, actor,
                                    ProposalService.payload("signature", sig, "lamports", lamports, "signer", signed.signer(), "blockhash", signed.tx().recentBlockhash())))
                            .publish(ProposalService.tradeEvent(next, TradeEvents.SUBMITTED, submitted,
                                    ProposalService.payload("signature", sig, "explorerUrl", next.execution().explorerUrl(), "lamports", lamports))));
                })
                .flatMap(s -> confirm(wallet.cluster(), s.execution().signature())
                        .doOnNext(status -> metrics.solanaConfirmationLatency(
                                Duration.between(s.execution().submittedAt(), clock.instant()), confirmationResult(status), wallet.cluster().id()))
                        .flatMap(status -> finish(s, status, actor, asset))
                        // An error while polling is not a failed trade: the row stays SUBMITTED for the reconciler.
                        .onErrorResume(ex -> {
                            log.warn("proposal_confirmation_interrupted proposalId={} signature={} error={}", s.id(), s.execution().signature(), ex.toString());
                            return Mono.just(s);
                        }))
                .onErrorResume(ex -> broadcastFailed(signedP, signed, ex, actor, asset));
    }

    /**
     * {@code sendTransaction} did not return a signature. If the node answered with a JSON-RPC
     * error it rejected the transaction and nothing is on the chain: FAILED. Anything else
     * (timeout, connection reset, our own DB refusing the SUBMITTED write) is <em>uncertain</em>:
     * the bytes may have reached the network, so the row keeps its signature and stays in flight.
     */
    private Mono<ActionProposal> broadcastFailed(ActionProposal signedP, Signed signed, Throwable ex, String actor, String asset) {
        if (ex instanceof SolanaRpcException rpcEx && rpcEx.getCause() == null) {
            return fail(signedP, actor, ex, CryptobotMetrics.FailureStage.RPC, asset);
        }
        log.warn("proposal_broadcast_uncertain proposalId={} signature={} error={}", signedP.id(), signed.signature(), ex.toString());
        Instant now = clock.instant();
        return proposals.commit(ProposalTransition.from(signedP, signedP.withExecution(signedP.execution(), now))
                        .audit(audit.event(signedP.ownerUserId(), signedP.walletId(), signedP.id(), EV_BROADCAST_UNCERTAIN, actor,
                                ProposalService.payload("signature", signed.signature(), "error", ex.getMessage()))))
                .onErrorResume(dbEx -> {
                    log.error("proposal_broadcast_uncertain_unrecorded proposalId={} error={}", signedP.id(), dbEx.toString());
                    return Mono.just(signedP);
                });
    }

    /** {@code failed} | {@code confirmed} | {@code finalized} | {@code pending}: the label of the latency timer and of the confirmed counter. */
    private static String confirmationResult(SolanaRpcClient.SignatureStatus status) {
        if (status.failed()) {
            return "failed";
        }
        return status.confirmationStatus() == null ? "pending" : status.confirmationStatus();
    }

    private Mono<SolanaRpcClient.SignatureStatus> confirm(SolanaCluster cluster, String signature) {
        return rpc.getSignatureStatus(cluster, signature)
                .flatMap(s -> {
                    if (s.failed()) {
                        return Mono.just(s);
                    }
                    if ("confirmed".equals(s.confirmationStatus()) || "finalized".equals(s.confirmationStatus())) {
                        return Mono.just(s);
                    }
                    return Mono.error(new Pending());
                })
                .retryWhen(Retry.fixedDelay(20, Duration.ofMillis(1500)).filter(Pending.class::isInstance)
                        .onRetryExhaustedThrow((spec, sig) -> new Pending()))
                .onErrorResume(Pending.class, ex -> Mono.just(new SolanaRpcClient.SignatureStatus(signature, "pending", false, null)));
    }

    private Mono<ActionProposal> finish(ActionProposal p, SolanaRpcClient.SignatureStatus status, String actor, String asset) {
        Instant now = clock.instant();
        ExecutionRecord prev = p.execution();
        if (status.failed()) {
            metrics.tradeFailed(CryptobotMetrics.FailureStage.ONCHAIN, asset);
            ActionProposal failed = p.withExecution(prev.withStatus(ExecutionRecord.FAILED, null, status.confirmationStatus(), status.error()), now)
                    .withStatus(ProposalStatus.FAILED, now);
            return proposals.commit(ProposalTransition.from(p, failed)
                    .audit(audit.event(p.ownerUserId(), p.walletId(), p.id(), EV_FAILED, actor, ProposalService.payload("signature", prev.signature(), "error", status.error())))
                    .publish(ProposalService.tradeEvent(failed, TradeEvents.FAILED, now, ProposalService.payload("signature", prev.signature(), "error", status.error(), "stage", "onchain"))))
                    .flatMap(terminal -> executionReceipts.receiptFor(terminal, p.execution().submittedAt()));
        }
        if ("pending".equals(status.confirmationStatus()) || status.confirmationStatus() == null) {
            // We stopped polling before the chain answered. The row stays SUBMITTED — honest — and
            // the ReconciliationService finishes it; the funnel counts the wait as "pending".
            metrics.tradeConfirmed("pending", asset);
            log.info("proposal_confirmation_pending proposalId={} signature={}", p.id(), prev.signature());
            return proposals.commit(ProposalTransition.from(p, p.withExecution(prev, now))
                    .audit(audit.event(p.ownerUserId(), p.walletId(), p.id(), EV_CONFIRMATION_PENDING, actor, ProposalService.payload("signature", prev.signature()))));
        }
        // Funnel step 4.
        metrics.tradeConfirmed(status.confirmationStatus(), asset);
        ActionProposal executed = p.withExecution(prev.withStatus(ExecutionRecord.EXECUTED, now, status.confirmationStatus(), null), now).withStatus(ProposalStatus.EXECUTED, now);
        log.info("proposal_executed proposalId={} signature={} confirmation={}", p.id(), prev.signature(), status.confirmationStatus());
        return proposals.commit(ProposalTransition.from(p, executed)
                .audit(audit.event(p.ownerUserId(), p.walletId(), p.id(), EV_EXECUTED, actor,
                        ProposalService.payload("signature", prev.signature(), "explorerUrl", prev.explorerUrl(), "confirmation", status.confirmationStatus())))
                .publish(ProposalService.tradeEvent(executed, TradeEvents.CONFIRMED, now,
                        ProposalService.payload("signature", prev.signature(), "explorerUrl", prev.explorerUrl(), "confirmation", status.confirmationStatus()))))
                .flatMap(terminal -> executionReceipts.receiptFor(terminal, p.execution().submittedAt()));
    }

    /** Only for failures where nothing can be on the chain: before signing, or a node-side rejection of the broadcast. */
    private Mono<ActionProposal> fail(ActionProposal executing, String actor, Throwable ex, CryptobotMetrics.FailureStage stage, String asset) {
        Instant now = clock.instant();
        // An RPC failure is counted as such whatever step it interrupted: it is the dependency, not the step.
        metrics.tradeFailed(ex instanceof SolanaRpcException ? CryptobotMetrics.FailureStage.RPC : stage, asset);
        log.warn("proposal_execution_failed proposalId={} stage={} error={}", executing.id(), stage, ex.toString());
        ExecutionRecord prev = executing.execution();
        ExecutionRecord rec = prev == null
                ? new ExecutionRecord(ExecutionRecord.FAILED, null, null, null, now, null, null, ex.getMessage(), null, null, 0, null)
                : prev.withStatus(ExecutionRecord.FAILED, null, null, ex.getMessage());
        ActionProposal failed = executing.withExecution(rec, now).withStatus(ProposalStatus.FAILED, now);
        return proposals.commit(ProposalTransition.from(executing, failed)
                .audit(audit.event(executing.ownerUserId(), executing.walletId(), executing.id(), EV_FAILED, actor, ProposalService.payload("error", ex.getMessage(), "stage", stage.name())))
                .publish(ProposalService.tradeEvent(failed, TradeEvents.FAILED, now, ProposalService.payload("error", ex.getMessage(), "stage", stage.name().toLowerCase(java.util.Locale.ROOT)))))
                .flatMap(terminal -> executionReceipts.receiptFor(terminal, executing.updatedAt()));
    }

    private static final class Pending extends RuntimeException {
        Pending() {
            super("pending", null, false, false);
        }
    }
}
