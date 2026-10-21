package io.lifeengine.cryptobot.api.controlplane;

import io.lifeengine.cryptobot.application.controlplane.AuditService;
import io.lifeengine.cryptobot.application.controlplane.ControlPlaneExceptions;
import io.lifeengine.cryptobot.application.controlplane.ProposalService;
import io.lifeengine.cryptobot.application.receipt.ReceiptService;
import io.lifeengine.cryptobot.core.receipts.IntelligenceReceipt;
import io.lifeengine.cryptobot.core.receipts.ReceiptKind;
import io.lifeengine.cryptobot.security.CryptobotPrincipal;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * (TAE fase 2, mandato §28): {@code GET /executions/{id}} and {@code GET
 * /executions/{id}/receipt} — read-only aliases over the exact same action proposal
 * {@code /proposals/{id}} already serves ("execution" and "proposal" are the same resource,
 * mandate: no rename); {@code /receipt} is new only in the sense that {@code /proposals/{id}
 * /receipts} (plural, every step) had no singular "the EXECUTION step" shortcut before.
 */
@RestController
@RequestMapping(path = "/api/cryptobot/executions", produces = "application/json")
public class ExecutionsController {

    private final ProposalService proposals;
    private final AuditService audit;
    private final ReceiptService receipts;

    public ExecutionsController(ProposalService proposals, AuditService audit, ReceiptService receipts) {
        this.proposals = proposals;
        this.audit = audit;
        this.receipts = receipts;
    }

    /** Byte-for-byte the same body {@link ProposalsController#get} returns for the same id. */
    @GetMapping("/{id}")
    public Mono<ControlPlaneDtos.ProposalView> get(@PathVariable UUID id, @AuthenticationPrincipal CryptobotPrincipal principal) {
        CryptobotPrincipal p = Principals.require(principal);
        return proposals.require(p.userId(), id)
                .flatMap(pr -> audit.forProposal(pr.id()).collectList().map(ev -> new ControlPlaneDtos.ProposalView(pr, ev)));
    }

    /**
     * The {@code EXECUTION} receipt of this proposal (Endgame §6: {@code STRATEGY → RISK_DECISION /
     * SIMULATION → EXECUTION}) — one at most, issued only at a terminal state ({@code EXECUTED}/
     * {@code FAILED}, see {@code ExecutionReceipts}). 404 before that, same as any other unknown id.
     */
    @GetMapping("/{id}/receipt")
    public Mono<IntelligenceReceipt> receipt(@PathVariable UUID id, @AuthenticationPrincipal CryptobotPrincipal principal) {
        CryptobotPrincipal p = Principals.require(principal);
        return proposals.require(p.userId(), id)
                .flatMapMany(pr -> receipts.forProposal(pr.id()))
                .filter(r -> r.kind() == ReceiptKind.EXECUTION)
                .next()
                .switchIfEmpty(Mono.error(new ControlPlaneExceptions.NotFound("Execution receipt for " + id)));
    }
}
