package io.lifeengine.cryptobot.application.controlplane;

import io.lifeengine.cryptobot.adapters.solana.Base58;
import io.lifeengine.cryptobot.adapters.solana.SolanaCluster;
import io.lifeengine.cryptobot.adapters.solana.SolanaRpcClient;
import io.lifeengine.cryptobot.adapters.solana.tx.SolanaKeypair;
import io.lifeengine.cryptobot.domain.transactions.ActionProposal;
import io.lifeengine.cryptobot.domain.transactions.ExecutionRecord;
import io.lifeengine.cryptobot.domain.transactions.PreparedTransaction;
import io.lifeengine.cryptobot.domain.transactions.ProposalStatus;
import io.lifeengine.cryptobot.domain.wallet.Wallet;
import io.lifeengine.cryptobot.integration.signer.SignerClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

/**
 * The only path to the chain. Requires an APPROVED proposal, re-checks policy, rebuilds the
 * transaction on a fresh blockhash, re-simulates, asks the isolated signer, verifies the
 * signature against the wallet's public key, broadcasts, and waits for confirmation.
 */
@Service
public class ExecutionService {

    private static final Logger log = LoggerFactory.getLogger(ExecutionService.class);
    public static final String EV_SUBMITTED = "EXECUTION_SUBMITTED";
    public static final String EV_EXECUTED = "EXECUTED";
    public static final String EV_FAILED = "EXECUTION_FAILED";

    private final ProposalService proposals;
    private final WalletService wallets;
    private final SimulationService simulation;
    private final PolicyEngine policy;
    private final SignerClient signer;
    private final SolanaRpcClient rpc;
    private final AuditService audit;
    private final Clock clock;

    public ExecutionService(ProposalService proposals, WalletService wallets, SimulationService simulation, PolicyEngine policy,
            SignerClient signer, SolanaRpcClient rpc, AuditService audit) {
        this.proposals = proposals;
        this.wallets = wallets;
        this.simulation = simulation;
        this.policy = policy;
        this.signer = signer;
        this.rpc = rpc;
        this.audit = audit;
        this.clock = Clock.systemUTC();
    }

    public Mono<ActionProposal> execute(UUID ownerUserId, UUID proposalId, String actor) {
        return proposals.require(ownerUserId, proposalId).flatMap(p -> {
            List<String> problems = policy.executionPreconditions(p);
            if (!problems.isEmpty()) {
                return Mono.error(new ControlPlaneExceptions.Conflict(String.join("; ", problems)));
            }
            return wallets.require(ownerUserId, p.walletId()).flatMap(wallet -> run(p, wallet, actor));
        });
    }

    private Mono<ActionProposal> run(ActionProposal p, Wallet wallet, String actor) {
        Instant start = clock.instant();
        ActionProposal executing = p.withStatus(ProposalStatus.EXECUTING, start);
        long lamports = p.transaction().lamports();
        return proposals.save(executing)
                // 1. Fresh blockhash: the one from approval time is almost certainly expired.
                .flatMap(saved -> simulation.prepareTransfer(wallet, lamports))
                // 2. Re-simulate the exact bytes that will be signed.
                .flatMap(tx -> rpc.simulateTransaction(wallet.cluster(), tx.unsignedTransactionBase64(), false)
                        .flatMap(sim -> sim.ok() ? Mono.just(tx)
                                : Mono.error(new ControlPlaneExceptions.Conflict("Pre-flight simulation failed: " + sim.error()))))
                // 3. Sign in the isolated signer, then verify the signature ourselves.
                .flatMap(tx -> signer.sign(p.id(), tx.unsignedTransactionBase64(), wallet.address())
                        .map(resp -> verifySigned(tx, resp, wallet)))
                // 4. Broadcast.
                .flatMap(signed -> rpc.sendTransaction(wallet.cluster(), signed.signedBase64())
                        .flatMap(sig -> {
                            Instant submitted = clock.instant();
                            ExecutionRecord rec = new ExecutionRecord("SUBMITTED", sig, wallet.cluster().explorerTxUrl(sig), signed.signer(), submitted, null, null, null);
                            return proposals.save(executing.withExecution(rec, submitted))
                                    .flatMap(s -> audit.record(s.ownerUserId(), s.walletId(), s.id(), EV_SUBMITTED, actor,
                                            ProposalService.payload("signature", sig, "lamports", lamports, "signer", signed.signer(), "blockhash", signed.tx().recentBlockhash())).thenReturn(s))
                                    .flatMap(s -> confirm(wallet.cluster(), sig).map(status -> finish(s, status, actor)).flatMap(m -> m));
                        }))
                .onErrorResume(ex -> fail(executing, actor, ex));
    }

    private record Signed(PreparedTransaction tx, String signedBase64, String signer) {}

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
        return new Signed(tx, resp.signedTransactionBase64(), resp.signer());
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

    private Mono<ActionProposal> finish(ActionProposal p, SolanaRpcClient.SignatureStatus status, String actor) {
        Instant now = clock.instant();
        ExecutionRecord prev = p.execution();
        if (status.failed()) {
            ExecutionRecord rec = new ExecutionRecord("FAILED", prev.signature(), prev.explorerUrl(), prev.signerPublicKey(), prev.submittedAt(), null, status.confirmationStatus(), status.error());
            return proposals.save(p.withExecution(rec, now).withStatus(ProposalStatus.FAILED, now))
                    .flatMap(s -> audit.record(s.ownerUserId(), s.walletId(), s.id(), EV_FAILED, actor, ProposalService.payload("signature", prev.signature(), "error", status.error())).thenReturn(s));
        }
        ExecutionRecord rec = new ExecutionRecord("EXECUTED", prev.signature(), prev.explorerUrl(), prev.signerPublicKey(), prev.submittedAt(), now, status.confirmationStatus(), null);
        log.info("proposal_executed proposalId={} signature={} confirmation={}", p.id(), prev.signature(), status.confirmationStatus());
        return proposals.save(p.withExecution(rec, now).withStatus(ProposalStatus.EXECUTED, now))
                .flatMap(s -> audit.record(s.ownerUserId(), s.walletId(), s.id(), EV_EXECUTED, actor,
                        ProposalService.payload("signature", prev.signature(), "explorerUrl", prev.explorerUrl(), "confirmation", status.confirmationStatus())).thenReturn(s));
    }

    private Mono<ActionProposal> fail(ActionProposal executing, String actor, Throwable ex) {
        Instant now = clock.instant();
        log.warn("proposal_execution_failed proposalId={} error={}", executing.id(), ex.toString());
        ExecutionRecord rec = new ExecutionRecord("FAILED", null, null, null, now, null, null, ex.getMessage());
        return proposals.save(executing.withExecution(rec, now).withStatus(ProposalStatus.FAILED, now))
                .flatMap(s -> audit.record(s.ownerUserId(), s.walletId(), s.id(), EV_FAILED, actor, ProposalService.payload("error", ex.getMessage())).thenReturn(s));
    }

    private static final class Pending extends RuntimeException {
        Pending() {
            super("pending", null, false, false);
        }
    }
}
