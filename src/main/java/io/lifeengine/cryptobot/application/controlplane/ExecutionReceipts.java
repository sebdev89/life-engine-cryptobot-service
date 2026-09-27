package io.lifeengine.cryptobot.application.controlplane;

import io.lifeengine.cryptobot.application.receipt.ReceiptService;
import io.lifeengine.cryptobot.core.execution.ActionProposal;
import io.lifeengine.cryptobot.core.execution.ProposalStatus;
import io.lifeengine.cryptobot.observability.CryptobotMetrics;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * The {@code EXECUTION} receipt of a proposal that reached a terminal state ({@code EXECUTED} or
 * {@code FAILED}) — KAN-391. Shared by {@link ExecutionService} (the synchronous path) and the
 * reconciler (the attempts the service could not finish), so both terminal paths leave the same
 * receipt: parents are the proposal's {@code SIMULATION} (DERIVES_FROM) and {@code STRATEGY}
 * (EXECUTES); inputs the transaction message, the policy verdict hash and the approval; the nonce
 * is the operation id, so one attempt ⇒ one receipt whichever path closes it.
 *
 * <p>Never fails the trade: the chain already answered. A receipt that could not be written is
 * logged, counted ({@code intelligence_receipts_total{result="failed"}}) and left for a re-issue —
 * not retried into a second transaction.
 */
@Component
public class ExecutionReceipts {

    private static final Logger log = LoggerFactory.getLogger(ExecutionReceipts.class);

    private final ReceiptService receipts;
    private final Receipts receiptOf;
    private final CryptobotMetrics metrics;

    @Autowired
    public ExecutionReceipts(ReceiptService receipts, Receipts receiptOf, CryptobotMetrics metrics) {
        this.receipts = receipts;
        this.receiptOf = receiptOf;
        this.metrics = metrics;
    }

    public Mono<ActionProposal> receiptFor(ActionProposal terminal, Instant startedAt) {
        if (terminal.status() != ProposalStatus.EXECUTED && terminal.status() != ProposalStatus.FAILED) {
            return Mono.just(terminal);
        }
        String tenant = Receipts.tenantOf(terminal.ownerUserId());
        Mono<Optional<String>> sim = receipts.byNonce(tenant, "sim:" + terminal.id())
                .map(r -> Optional.of(r.receiptHash())).defaultIfEmpty(Optional.empty());
        Mono<Optional<String>> strategy = receipts.byNonce(tenant, terminal.id().toString())
                .map(r -> Optional.of(r.receiptHash())).defaultIfEmpty(Optional.empty());
        return Mono.zip(sim, strategy)
                .flatMap(t -> receipts.issue(receiptOf.execution(terminal, t.getT1().orElse(null), t.getT2().orElse(null), startedAt)))
                .doOnError(ex -> {
                    metrics.intelligenceReceipt("failed");
                    log.error("execution_receipt_failed proposalId={} status={} error={}", terminal.id(), terminal.status(), ex.toString());
                })
                .onErrorResume(ex -> Mono.empty())
                .thenReturn(terminal);
    }
}
