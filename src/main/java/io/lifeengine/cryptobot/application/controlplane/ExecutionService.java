package io.lifeengine.cryptobot.application.controlplane;

import io.lifeengine.cryptobot.solana.rpc.Base58;
import io.lifeengine.cryptobot.solana.rpc.ExecutionProperties;
import io.lifeengine.cryptobot.solana.rpc.MainnetDisabledException;
import io.lifeengine.cryptobot.solana.rpc.SolanaCluster;
import io.lifeengine.cryptobot.solana.rpc.SolanaRpcClient;
import io.lifeengine.cryptobot.solana.rpc.SolanaRpcException;
import io.lifeengine.cryptobot.solana.tx.SolanaKeypair;
import io.lifeengine.cryptobot.application.oracle.PriceOracleService;
import io.lifeengine.cryptobot.core.reliability.TradeEvents;
import io.lifeengine.cryptobot.core.execution.ActionProposal;
import io.lifeengine.cryptobot.core.execution.ExecutionRecord;
import io.lifeengine.cryptobot.core.execution.PreparedTransaction;
import io.lifeengine.cryptobot.core.execution.ProposalStatus;
import io.lifeengine.cryptobot.core.execution.ProposalTransition;
import io.lifeengine.cryptobot.core.policy.PolicyInput;
import io.lifeengine.cryptobot.core.policy.PolicyVerdict;
import io.lifeengine.cryptobot.core.wallet.Wallet;
import io.lifeengine.cryptobot.integration.signer.SignerClient;
import io.lifeengine.cryptobot.integration.validator.ValidatorClient;
import io.lifeengine.cryptobot.observability.CryptobotMetrics;
import io.micrometer.core.instrument.Timer;
import io.lifeengine.cryptobot.observability.ErrorCode;
import io.lifeengine.cryptobot.observability.LogContext;
import io.lifeengine.cryptobot.observability.LogFields;
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
 * The only path to the chain. Requires an APPROVED proposal whose timelock has elapsed, re-checks
 * policy, rebuilds the transaction on a fresh blockhash, re-simulates, has the <em>independent
 * validator</em> re-derive the verdict and attest these exact bytes (paper §20), asks
 * the isolated signer — which refuses without that attestation —, verifies the signature against
 * the wallet's public key, broadcasts, and waits for confirmation.
 *
 * <h2>Reliability</h2>
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
 *   <li><b>Idempotent retry.</b> Once that blockhash has expired and the chain has never
 *       seen the signature, the transaction can no longer land: {@link #retry} runs the pipeline
 *       again under the <em>same</em> {@code operationId} — fresh blockhash, re-simulation, a new
 *       attestation, a new signature — and records {@code EXECUTION_RETRIED} with the superseded
 *       signature. Only the {@code ReconciliationService} calls it, and only after proving that.
 * </ul>
 *
 * <h2>Mainnet is fail-closed</h2>
 *
 * Before the proposal is even moved to {@code EXECUTING}, a wallet or proposal on mainnet is
 * refused with {@link MainnetDisabledException} ({@code 409 MAINNET_DISABLED}) unless
 * {@code cryptobot.execution.allow-mainnet=true}. {@code PolicyEngine}'s {@code EXECUTION_CLUSTER}
 * rule, {@code SolanaRpcClient.sendTransaction} and the signer each check independently.
 */
@Service
public class ExecutionService {

    private static final Logger log = LoggerFactory.getLogger(ExecutionService.class);
    public static final String EV_STARTED = "EXECUTION_STARTED";
    /** the independent validator re-derived the verdict and attested the bytes. */
    public static final String EV_VALIDATED = "EXECUTION_VALIDATED";
    public static final String EV_SIGNED = "EXECUTION_SIGNED";
    public static final String EV_SUBMITTED = "EXECUTION_SUBMITTED";
    public static final String EV_BROADCAST_UNCERTAIN = "EXECUTION_BROADCAST_UNCERTAIN";
    /** the reconciler re-executed the operation (same operationId) after the previous signature's blockhash expired unseen. */
    public static final String EV_RETRIED = "EXECUTION_RETRIED";
    public static final String EV_CONFIRMATION_PENDING = "EXECUTION_CONFIRMATION_PENDING";
    public static final String EV_DUPLICATE_SUPPRESSED = "EXECUTION_DUPLICATE_SUPPRESSED";
    public static final String EV_EXECUTED = "EXECUTED";
    public static final String EV_FAILED = "EXECUTION_FAILED";

    private final ProposalService proposals;
    private final WalletService wallets;
    private final SimulationService simulation;
    private final PolicyEngine policy;
    private final SignerClient signer;
    private final ValidatorClient validator;
    private final SolanaRpcClient rpc;
    private final PriceOracleService oracle;
    private final AuditService audit;
    private final CryptobotMetrics metrics;
    private final ExecutionReceipts executionReceipts;
    private final ExecutionProperties execution;
    private final Clock clock;

    public ExecutionService(ProposalService proposals, WalletService wallets, SimulationService simulation, PolicyEngine policy,
            SignerClient signer, ValidatorClient validator, SolanaRpcClient rpc, PriceOracleService oracle, AuditService audit,
            CryptobotMetrics metrics, ExecutionReceipts executionReceipts, ExecutionProperties execution) {
        this.proposals = proposals;
        this.wallets = wallets;
        this.simulation = simulation;
        this.policy = policy;
        this.signer = signer;
        this.validator = validator;
        this.rpc = rpc;
        this.oracle = oracle;
        this.audit = audit;
        this.metrics = metrics;
        this.executionReceipts = executionReceipts;
        this.execution = execution == null ? ExecutionProperties.failClosed() : execution;
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
        return execute(ownerUserId, proposalId, actor, operationId, null);
    }

    /**
     * (CB-03): when the idempotency key was an intent hash, {@code intentHash} is
     * the {@code sha256:…} the caller presented and {@code operationId} derives from it. It is
     * persisted with the row ({@code action_proposal.intent_hash}) in the commit that moves it to
     * {@code EXECUTING} and recorded in the {@code EXECUTION_STARTED} event. {@code null} for a UUID key.
     */
    public Mono<ActionProposal> execute(UUID ownerUserId, UUID proposalId, String actor, UUID operationId, String intentHash) {
        // toda línea del pipeline lleva proposalId/operationId en el MDC (LogContext), del
        // preflight al recibo: en Loki `| json | proposalId="…"` es la historia de la propuesta.
        return doExecute(ownerUserId, proposalId, actor, operationId, intentHash)
                .contextWrite(ctx -> LogContext.proposal(ctx, proposalId, operationId));
    }

    private Mono<ActionProposal> doExecute(UUID ownerUserId, UUID proposalId, String actor, UUID operationId, String intentHash) {
        return proposals.require(ownerUserId, proposalId).flatMap(p -> {
            if (operationId.equals(p.operationId())) {
                return suppressDuplicate(p, actor);
            }
            if (p.status().inFlight()) {
                metrics.executionRefused(CryptobotMetrics.RefusalReason.STATE);
                return Mono.error(new ControlPlaneExceptions.Conflict("Proposal is " + p.status() + " under operation " + p.operationId()
                        + "; retry with the same operationId or wait for the result"));
            }
            // the policy stage — preconditions plus the fresh oracle reading — has its own histogram,
            // and a 409 here is counted by its most specific reason (cryptobot_execution_refused_total{reason}).
            Timer.Sample policyStage = metrics.stageStart();
            List<PolicyEngine.Refusal> refusals = policy.executionRefusals(p);
            if (!refusals.isEmpty()) {
                List<String> problems = refusals.stream().map(PolicyEngine.Refusal::message).toList();
                CryptobotMetrics.RefusalReason reason = PolicyEngine.primaryRefusal(refusals);
                metrics.executionRefused(reason);
                metrics.stageStop(CryptobotMetrics.Stage.POLICY, policyStage);
                log.warn("execution_precondition_failed proposalId={} reason={} problems={}", p.id(), reason.label(), problems,
                        LogFields.event("execution_refused"), LogFields.status("refused"), ErrorCode.EXECUTION_PRECONDITION.kv());
                return Mono.error(new ControlPlaneExceptions.Conflict(String.join("; ", problems)));
            }
            // the envelope's data-integrity assumptions are re-checked against a fresh reading right
            // before anything is signed — quorum, deviation, the breaker, and the plan's price vs. the world now.
            return oracle.read(ProposalService.assetsOf(p.plan())).flatMap(reading -> {
                List<String> oracleProblems = policy.priceViolations(p, reading).stream().map(v -> v.rule() + ": " + v.message()).toList();
                metrics.stageStop(CryptobotMetrics.Stage.POLICY, policyStage);
                if (!oracleProblems.isEmpty()) {
                    metrics.oracleExecutionRefused();
                    metrics.executionRefused(CryptobotMetrics.RefusalReason.ORACLE);
                    log.warn("execution_oracle_refused proposalId={} problems={} quotesHash={}", p.id(), oracleProblems, reading.quotesHash(),
                            LogFields.event("execution_refused"), LogFields.status("refused"), ErrorCode.ORACLE_REFUSED.kv());
                    return Mono.error(new ControlPlaneExceptions.Conflict("Oracle refused execution: " + String.join("; ", oracleProblems)));
                }
                return wallets.require(ownerUserId, p.walletId())
                        .flatMap(wallet -> requireClusterAllowed(p, wallet))
                        .flatMap(wallet -> start(p, operationId, intentHash, actor)
                                .flatMap(executing -> executing.operationId().equals(operationId) && executing.status() == ProposalStatus.EXECUTING
                                        && executing.execution() == null
                                        ? run(executing, wallet, actor)
                                        : Mono.just(executing)));
            });
        });
    }

    /**
     * the first of the three mainnet guards. Checked on the wallet's cluster <em>and</em>
     * the cluster recorded on the proposal, before any state change and before the validator or
     * the signer is asked. No flag ⇒ no mainnet, whatever the policy said.
     */
    private Mono<Wallet> requireClusterAllowed(ActionProposal p, Wallet wallet) {
        SolanaCluster walletCluster = SolanaCluster.from(wallet.cluster());
        SolanaCluster proposalCluster;
        try {
            proposalCluster = SolanaCluster.parse(p.cluster());
        } catch (IllegalArgumentException unknown) {
            metrics.executionRefused(CryptobotMetrics.RefusalReason.STATE);
            return Mono.error(new ControlPlaneExceptions.Conflict("Proposal " + p.id() + " is on an unknown cluster: " + p.cluster()));
        }
        SolanaCluster refused = !execution.permits(walletCluster) ? walletCluster : !execution.permits(proposalCluster) ? proposalCluster : null;
        if (refused != null) {
            metrics.executionRefused(CryptobotMetrics.RefusalReason.MAINNET);
            log.warn("execution_mainnet_disabled proposalId={} walletCluster={} proposalCluster={}", p.id(), walletCluster.id(), p.cluster(),
                    LogFields.event("execution_refused"), LogFields.status("refused"), ErrorCode.MAINNET_DISABLED.kv());
            return Mono.error(new MainnetDisabledException("execute", refused));
        }
        if (proposalCluster != walletCluster) {
            metrics.executionRefused(CryptobotMetrics.RefusalReason.STATE);
            return Mono.error(new ControlPlaneExceptions.Conflict("Proposal " + p.id() + " was prepared for " + p.cluster() + "; the wallet is on " + walletCluster.id()));
        }
        return Mono.just(wallet);
    }

    /**
     * the reconciler's idempotent retry. Preconditions the caller proved against the
     * chain: the row is in flight ({@code EXECUTING}/{@code SUBMITTED}) with a signature that was
     * never seen and whose blockhash has expired, so the previous bytes can never be included.
     * The operation keeps its {@code operationId}; the first commit ({@code SIGNED} with the new
     * signature) is guarded by the version the caller read, so a live request or a second
     * reconciler cannot retry the same row twice.
     */
    public Mono<ActionProposal> retry(ActionProposal p, String actor) {
        if (!p.status().inFlight() || p.execution() == null || !p.execution().hasSignature() || p.operationId() == null) {
            return Mono.error(new ControlPlaneExceptions.Conflict("Proposal " + p.id() + " is not an in-flight signed execution; nothing to retry"));
        }
        return wallets.require(p.ownerUserId(), p.walletId())
                .flatMap(wallet -> requireClusterAllowed(p, wallet))
                .flatMap(wallet -> {
                    log.warn("execution_retry proposalId={} operationId={} retry={} previousSignature={}", p.id(), p.operationId(),
                            p.execution().retries() + 1, p.execution().signature(),
                            LogFields.event("execution_retry"), LogFields.status("retrying"));
                    return run(p, wallet, actor, true);
                })
                .contextWrite(ctx -> LogContext.proposal(ctx, p.id(), p.operationId()));
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
    private Mono<ActionProposal> start(ActionProposal p, UUID operationId, String intentHash, String actor) {
        Instant now = clock.instant();
        ActionProposal executing = p.withOperation(operationId, intentHash, now).withStatus(ProposalStatus.EXECUTING, now);
        return proposals.commit(ProposalTransition.from(p, executing)
                        .audit(audit.event(p.ownerUserId(), p.walletId(), p.id(), EV_STARTED, actor,
                                ProposalService.payload("operationId", operationId, "intentHash", intentHash))))
                .onErrorResume(ControlPlaneExceptions.StaleProposal.class, stale -> proposals.require(p.ownerUserId(), p.id())
                        .flatMap(fresh -> operationId.equals(fresh.operationId()) ? suppressDuplicate(fresh, actor) : Mono.error(stale)));
    }

    private record Signed(PreparedTransaction tx, String signedBase64, String signer, String signature, ValidatorClient.Response attestation) {}

    private record Attested(PreparedTransaction tx, ValidatorClient.Response attestation) {}

    private record Step(ActionProposal proposal, Signed signed) {}

    private Mono<ActionProposal> run(ActionProposal executing, Wallet wallet, String actor) {
        return run(executing, wallet, actor, false);
    }

    /** {@code retry}: the row already carries a superseded signature; the SIGNED commit records the retry. */
    private Mono<ActionProposal> run(ActionProposal executing, Wallet wallet, String actor, boolean retry) {
        long lamports = executing.transaction().lamports();
        String asset = ProposalService.assetOf(executing.plan());
        // Which step of the pipeline we are in, so a failure is counted where it happened
        // ("dónde se cae"), not just as "failed".
        AtomicReference<CryptobotMetrics.FailureStage> stage = new AtomicReference<>(CryptobotMetrics.FailureStage.PREFLIGHT);
        // 1. Fresh blockhash: the one from approval time is almost certainly expired.
        //    each stage runs under its own histogram (cryptobot_stage_latency_seconds{stage}).
        return timed(CryptobotMetrics.Stage.SIMULATE, () -> simulation.prepareTransfer(wallet, lamports)
                // 1b. an internal ticket (audit G1): prepareTransfer just re-read cryptobot.policy.rebalance-vault
                //     from live config — bind it to what was actually approved before anything downstream sees it.
                .flatMap(tx -> requireDestinationBound(executing, tx))
                // 2. Re-simulate the exact bytes that will be signed.
                .flatMap(tx -> rpc.simulateTransaction(SolanaCluster.from(wallet.cluster()), tx.unsignedTransactionBase64(), false)
                        .flatMap(sim -> sim.ok() ? Mono.just(tx)
                                : Mono.error(new ControlPlaneExceptions.Conflict("Pre-flight simulation failed: " + sim.error())))))
                // 3. Independent validation (paper §20): a separate process re-derives the verdict
                //    over the recorded (I, S) under its own pinned H_R and attests THESE bytes. Disagreement,
                //    DENY, or no answer ⇒ nothing is signed.
                .doOnNext(tx -> stage.set(CryptobotMetrics.FailureStage.VALIDATE))
                .flatMap(tx -> timed(CryptobotMetrics.Stage.VALIDATE, () -> validator.authorize(executing, tx)
                        .doOnNext(att -> {
                            metrics.validatorAttestation("issued");
                            log.info("execution_validated proposalId={} validator={} decision={} verdictHash={} expiresAt={}",
                                    executing.id(), att.attestation().validator(), att.decision(), att.verdictHash(), att.expires());
                        })
                        .doOnError(ValidatorClient.ValidatorRefused.class, ex -> metrics.validatorAttestation("refused"))
                        .map(att -> new Attested(tx, att))))
                // 4. Sign in the isolated signer (which checks the attestation itself), then verify the signature ourselves.
                .doOnNext(a -> stage.set(CryptobotMetrics.FailureStage.SIGN))
                .flatMap(a -> timed(CryptobotMetrics.Stage.SIGN, () -> signer.sign(executing.id(), a.tx().unsignedTransactionBase64(), wallet.address(), SolanaCluster.from(wallet.cluster()), a.attestation().attestation())
                        .map(resp -> verifySigned(a.tx(), resp, wallet, a.attestation()))))
                // 5. Persist the signature BEFORE broadcasting: from here on a crash is reconcilable.
                .flatMap(signed -> persistSigned(executing, signed, wallet, actor, retry).map(s -> new Step(s, signed)))
                // Anything up to here failed before the chain could have seen the transaction: safe to FAILED.
                .onErrorResume(ex -> fail(executing, actor, ex, stage.get(), asset).map(p -> new Step(p, null)))
                .flatMap(step -> step.signed() == null ? Mono.just(step.proposal()) : broadcast(step.proposal(), step.signed(), wallet, actor, asset));
    }

    /**
     * (audit G1, §17): {@code SimulationService.prepareTransfer} rebuilds the transaction
     * from {@code cryptobot.policy.rebalance-vault} <em>at execution time</em> — before this check
     * nothing compared the freshly-read destination with what the human actually approved
     * ({@code executing.transaction()}, the {@link PreparedTransaction} persisted when the proposal
     * was simulated/created, unchanged by approval). If the config moved between the two —
     * intentionally or by a compromised deploy — the destination or the lamports diverge here,
     * before the validator or the signer are ever asked. Runs on {@link #retry} too (same {@link
     * #run} code path). {@code SIGNER_ALLOWED_DESTINATIONS} on the isolated signer remains
     * the last independent barrier; this closes the gap in the service itself.
     */
    private static Mono<PreparedTransaction> requireDestinationBound(ActionProposal executing, PreparedTransaction fresh) {
        PreparedTransaction approved = executing.transaction();
        if (approved == null) {
            return Mono.error(new ControlPlaneExceptions.Conflict(
                    "destination_mismatch: proposal " + executing.id() + " has no approved transaction to bind execution to"));
        }
        if (!approved.destination().equals(fresh.destination()) || approved.lamports() != fresh.lamports()) {
            return Mono.error(new ControlPlaneExceptions.Conflict("destination_mismatch: approved " + approved.destination() + "/"
                    + approved.lamports() + " lamports, rebuilt at execution time " + fresh.destination() + "/" + fresh.lamports()
                    + " lamports — cryptobot.policy.rebalance-vault (or the amount) changed between approval and execution"));
        }
        return Mono.just(fresh);
    }

    /** Defence in depth: the signer's output must be the same message we sent, signed by the wallet key. */
    private static Signed verifySigned(PreparedTransaction tx, SignerClient.SignResponse resp, Wallet wallet, ValidatorClient.Response attestation) {
        return verifySigned(tx, resp, wallet.address(), attestation);
    }

    private static Signed verifySigned(PreparedTransaction tx, SignerClient.SignResponse resp, String feePayer, ValidatorClient.Response attestation) {
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
        if (!SolanaKeypair.verify(Base58.decode(feePayer), message, signature)) {
            throw new ControlPlaneExceptions.Conflict("Signature does not verify against the wallet public key");
        }
        // The transaction id IS the first signature: known before anyone broadcasts it.
        return new Signed(tx, resp.signedTransactionBase64(), resp.signer(), Base58.encode(signature), attestation);
    }

    private Mono<ActionProposal> persistSigned(ActionProposal executing, Signed signed, Wallet wallet, String actor, boolean retry) {
        Instant now = clock.instant();
        String explorer = SolanaCluster.from(wallet.cluster()).explorerTxUrl(signed.signature());
        ExecutionRecord prev = executing.execution();
        ExecutionRecord rec = retry
                ? prev.retriedWith(signed.signature(), explorer, signed.signer(), signed.tx().recentBlockhash(), signed.tx().lastValidBlockHeight())
                : new ExecutionRecord(ExecutionRecord.SIGNED, signed.signature(), explorer,
                        signed.signer(), null, null, null, null, signed.tx().recentBlockhash(), signed.tx().lastValidBlockHeight(), 0, null);
        ValidatorClient.Response att = signed.attestation();
        ActionProposal next = executing.withExecution(rec, now);
        if (retry && executing.status() == ProposalStatus.SUBMITTED) {
            next = next.withStatus(ProposalStatus.EXECUTING, now); // back in the signed-not-broadcast state, under the same operation
        }
        ProposalTransition transition = ProposalTransition.from(executing, next);
        if (retry) {
            transition = transition.audit(audit.event(executing.ownerUserId(), executing.walletId(), executing.id(), EV_RETRIED, actor,
                    ProposalService.payload("operationId", executing.operationId(), "retry", rec.retries(), "previousSignature", prev.signature(),
                            "previousBlockhash", prev.recentBlockhash(), "previousLastValidBlockHeight", prev.lastValidBlockHeight(),
                            "signature", signed.signature(), "reason", "blockhash expired; signature never seen on chain")));
        }
        return proposals.commit(transition
                .audit(audit.event(executing.ownerUserId(), executing.walletId(), executing.id(), EV_VALIDATED, actor,
                                ProposalService.payload("validator", att.attestation().validator(), "decision", att.decision(), "escalation", att.escalation(),
                                        "policyHash", att.policyHash(), "verdictHash", att.verdictHash(), "inputHash", att.inputHash(),
                                        "attestationExpiresAt", att.expires(), "attestationSignature", att.attestation().signature())),
                        audit.event(executing.ownerUserId(), executing.walletId(), executing.id(), EV_SIGNED, actor,
                        ProposalService.payload("signature", signed.signature(), "signer", signed.signer(), "blockhash", signed.tx().recentBlockhash(),
                                "lastValidBlockHeight", signed.tx().lastValidBlockHeight(), "validator", att.attestation().validator()))));
    }

    private Mono<ActionProposal> broadcast(ActionProposal signedP, Signed signed, Wallet wallet, String actor, String asset) {
        long lamports = signedP.transaction().lamports();
        return timed(CryptobotMetrics.Stage.SUBMIT, () -> rpc.sendTransaction(SolanaCluster.from(wallet.cluster()), signed.signedBase64())
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
                }))
                .flatMap(s -> timed(CryptobotMetrics.Stage.CONFIRM, () -> confirm(SolanaCluster.from(wallet.cluster()), s.execution().signature()))
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
        if (ex instanceof MainnetDisabledException) {
            // the RPC client refused before sending anything — certain, nothing is on the chain.
            return fail(signedP, actor, ex, CryptobotMetrics.FailureStage.RPC, asset);
        }
        log.warn("proposal_broadcast_uncertain proposalId={} signature={} error={}", signedP.id(), signed.signature(), ex.toString(),
                LogFields.event("broadcast_uncertain"), LogFields.status("uncertain"), LogFields.stage("broadcast"), ErrorCode.BROADCAST_UNCERTAIN.kv());
        Instant now = clock.instant();
        return proposals.commit(ProposalTransition.from(signedP, signedP.withExecution(signedP.execution(), now))
                        .audit(audit.event(signedP.ownerUserId(), signedP.walletId(), signedP.id(), EV_BROADCAST_UNCERTAIN, actor,
                                ProposalService.payload("signature", signed.signature(), "error", ex.getMessage()))))
                .onErrorResume(dbEx -> {
                    log.error("proposal_broadcast_uncertain_unrecorded proposalId={} error={}", signedP.id(), dbEx.toString(),
                            LogFields.event("broadcast_uncertain"), LogFields.status("unrecorded"), LogFields.stage("broadcast"), ErrorCode.BROADCAST_UNCERTAIN.kv());
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
            log.warn("proposal_execution_failed proposalId={} stage=ONCHAIN signature={} error={}", p.id(), prev.signature(), status.error(),
                    LogFields.event("execution_failed"), LogFields.status("failed"), LogFields.stage("onchain"), ErrorCode.SOLANA_TX_FAILED.kv());
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
            log.info("proposal_confirmation_pending proposalId={} signature={}", p.id(), prev.signature(),
                    LogFields.event("confirmation_pending"), LogFields.status("pending"));
            return proposals.commit(ProposalTransition.from(p, p.withExecution(prev, now))
                    .audit(audit.event(p.ownerUserId(), p.walletId(), p.id(), EV_CONFIRMATION_PENDING, actor, ProposalService.payload("signature", prev.signature()))));
        }
        // Funnel step 4.
        metrics.tradeConfirmed(status.confirmationStatus(), asset);
        ActionProposal executed = p.withExecution(prev.withStatus(ExecutionRecord.EXECUTED, now, status.confirmationStatus(), null), now).withStatus(ProposalStatus.EXECUTED, now);
        log.info("proposal_executed proposalId={} signature={} confirmation={}", p.id(), prev.signature(), status.confirmationStatus(),
                LogFields.event("executed"), LogFields.status("confirmed"));
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
        CryptobotMetrics.FailureStage counted = ex instanceof SolanaRpcException ? CryptobotMetrics.FailureStage.RPC : stage;
        metrics.tradeFailed(counted, asset);
        log.warn("proposal_execution_failed proposalId={} stage={} error={}", executing.id(), stage, ex.toString(),
                LogFields.event("execution_failed"), LogFields.status("failed"), LogFields.stage(counted.name()), errorCodeOf(ex, counted).kv());
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

    // ---- an internal ticket: a transfer that is not a proposal (Proof of Value payout) ------------------------------

    /**
     * One {@code SystemProgram.transfer(feePayer → destination, lamports)} through the same gates as {@link #execute}: the
     * mainnet guard, a fresh blockhash, an on-chain simulation of the exact bytes, the independent validator's attestation
     * over the recorded {@code (I, S)} and verdict, the isolated signer, our own check of its signature, and then the
     * broadcast and the confirmation poll. {@code operationId} is what the attestation and the signer bind the bytes to.
     */
    public record Transfer(UUID operationId, SolanaCluster cluster, String feePayer, String destination, long lamports, String label,
            PolicyVerdict verdict, PolicyInput input) {}

    /**
     * {@code status}: {@link #TRANSFER_CONFIRMED} (confirmed or finalized), {@link #TRANSFER_SUBMITTED} (signed and possibly on the
     * chain — broadcast uncertain or confirmation still pending: a reconciler asks {@link #transferStatus} later) or
     * {@link #TRANSFER_FAILED} (nothing is on the chain, or the chain rejected it; {@code error} says why, no secrets).
     */
    public record TransferResult(String status, String signature, String confirmation, String error, String validator) {}

    public static final String TRANSFER_CONFIRMED = "CONFIRMED";
    public static final String TRANSFER_SUBMITTED = "SUBMITTED";
    public static final String TRANSFER_FAILED = "FAILED";

    /**
     * {@link Transfer} through the execution pipeline without a proposal row. {@code onSigned} runs with the
     * signature after the signer answered and <em>before</em> the broadcast — the caller persists it there, so a crash
     * from then on leaves a row a reconciler can look up (the same rule as {@code SIGNED} for a proposal). Never errors:
     * every outcome is a {@link TransferResult}.
     */
    public Mono<TransferResult> submitTransfer(Transfer t, java.util.function.Function<String, Mono<?>> onSigned) {
        if (!execution.permits(t.cluster())) {
            metrics.executionRefused(CryptobotMetrics.RefusalReason.MAINNET);
            return Mono.just(new TransferResult(TRANSFER_FAILED, null, null, new MainnetDisabledException("transfer", t.cluster()).getMessage(), null));
        }
        AtomicReference<String> validatorKey = new AtomicReference<>();
        return timed(CryptobotMetrics.Stage.SIMULATE, () -> simulation.prepareTransfer(t.cluster(), t.feePayer(), t.destination(), t.lamports(), t.label())
                        .flatMap(tx -> rpc.simulateTransaction(t.cluster(), tx.unsignedTransactionBase64(), false)
                                .flatMap(sim -> sim.ok() ? Mono.just(tx)
                                        : Mono.error(new ControlPlaneExceptions.Conflict("Pre-flight simulation failed: " + sim.error())))))
                .flatMap(tx -> timed(CryptobotMetrics.Stage.VALIDATE, () -> validator.authorize(t.operationId(), t.verdict(), t.input(), tx)
                        .doOnNext(att -> {
                            metrics.validatorAttestation("issued");
                            validatorKey.set(att.attestation().validator());
                        })
                        .doOnError(ValidatorClient.ValidatorRefused.class, ex -> metrics.validatorAttestation("refused"))
                        .map(att -> new Attested(tx, att))))
                .flatMap(a -> timed(CryptobotMetrics.Stage.SIGN, () -> signer.sign(t.operationId(), a.tx().unsignedTransactionBase64(), t.feePayer(),
                                t.cluster(), a.attestation().attestation())
                        .map(resp -> verifySigned(a.tx(), resp, t.feePayer(), a.attestation()))))
                .flatMap(signed -> onSigned.apply(signed.signature()).then(Mono.just(signed)))
                // Up to here nothing can be on the chain.
                .flatMap(signed -> timed(CryptobotMetrics.Stage.SUBMIT, () -> rpc.sendTransaction(t.cluster(), signed.signedBase64()))
                        .then(Mono.defer(() -> timed(CryptobotMetrics.Stage.CONFIRM, () -> confirm(t.cluster(), signed.signature()))))
                        .map(status -> status.failed()
                                ? new TransferResult(TRANSFER_FAILED, signed.signature(), status.confirmationStatus(), "on-chain: " + status.error(),
                                        validatorKey.get())
                                : "pending".equals(status.confirmationStatus()) || status.confirmationStatus() == null
                                        ? new TransferResult(TRANSFER_SUBMITTED, signed.signature(), "pending", null, validatorKey.get())
                                        : new TransferResult(TRANSFER_CONFIRMED, signed.signature(), status.confirmationStatus(), null, validatorKey.get()))
                        // A JSON-RPC rejection (or the mainnet guard of the client) is certain: nothing landed. Anything else is uncertain.
                        .onErrorResume(ex -> Mono.just((ex instanceof SolanaRpcException rpcEx && rpcEx.getCause() == null) || ex instanceof MainnetDisabledException
                                ? new TransferResult(TRANSFER_FAILED, signed.signature(), null, "broadcast rejected: " + ex.getMessage(), validatorKey.get())
                                : new TransferResult(TRANSFER_SUBMITTED, signed.signature(), null, "broadcast uncertain: " + ex.getMessage(), validatorKey.get()))))
                .onErrorResume(ex -> {
                    log.warn("transfer_failed operationId={} destination={} lamports={} error={}", t.operationId(), t.destination(), t.lamports(), ex.toString(),
                            LogFields.event("transfer_failed"), LogFields.status("failed"));
                    return Mono.just(new TransferResult(TRANSFER_FAILED, null, null, ex.getMessage(), validatorKey.get()));
                });
    }

    /** one look at a submitted transfer's signature — the reconciler's question, without the retry loop. */
    public Mono<TransferResult> transferStatus(SolanaCluster cluster, String signature) {
        return rpc.getSignatureStatus(cluster, signature)
                .map(s -> s.failed()
                        ? new TransferResult(TRANSFER_FAILED, signature, s.confirmationStatus(), "on-chain: " + s.error(), null)
                        : "confirmed".equals(s.confirmationStatus()) || "finalized".equals(s.confirmationStatus())
                                ? new TransferResult(TRANSFER_CONFIRMED, signature, s.confirmationStatus(), null, null)
                                : new TransferResult(TRANSFER_SUBMITTED, signature, s.confirmationStatus(), null, null))
                .onErrorResume(ex -> Mono.just(new TransferResult(TRANSFER_SUBMITTED, signature, null, null, null)));
    }

    /** El código de una falla antes del broadcast: la dependencia que faltó, o el paso. */
    static ErrorCode errorCodeOf(Throwable ex, CryptobotMetrics.FailureStage stage) {
        if (ex instanceof MainnetDisabledException) {
            return ErrorCode.MAINNET_DISABLED;
        }
        if (ex instanceof SolanaRpcException) {
            return ErrorCode.SOLANA_RPC;
        }
        return switch (stage) {
            case VALIDATE -> ErrorCode.VALIDATOR_UNAVAILABLE;
            case SIGN -> ErrorCode.SIGNER_UNAVAILABLE;
            case RPC -> ErrorCode.SOLANA_RPC;
            case ONCHAIN -> ErrorCode.SOLANA_TX_FAILED;
            default -> ErrorCode.EXECUTION_FAILED;
        };
    }

    /** runs {@code step} under the stage's histogram; recorded on success, error and cancel alike. */
    private <T> Mono<T> timed(CryptobotMetrics.Stage stage, java.util.function.Supplier<Mono<T>> step) {
        return Mono.defer(() -> {
            Timer.Sample sample = metrics.stageStart();
            return step.get().doFinally(signal -> metrics.stageStop(stage, sample));
        });
    }

    private static final class Pending extends RuntimeException {
        Pending() {
            super("pending", null, false, false);
        }
    }
}
