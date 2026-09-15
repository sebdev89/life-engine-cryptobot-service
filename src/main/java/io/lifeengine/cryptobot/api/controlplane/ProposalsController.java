package io.lifeengine.cryptobot.api.controlplane;

import io.lifeengine.cryptobot.application.controlplane.AuditService;
import io.lifeengine.cryptobot.application.controlplane.ExecutionService;
import io.lifeengine.cryptobot.application.controlplane.ProposalService;
import io.lifeengine.cryptobot.domain.transactions.ActionProposal;
import io.lifeengine.cryptobot.domain.transactions.AuditEvent;
import io.lifeengine.cryptobot.security.CryptobotPrincipal;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping(path = "/api/cryptobot/proposals", produces = "application/json")
public class ProposalsController {

    private final ProposalService proposals;
    private final ExecutionService execution;
    private final AuditService audit;

    public ProposalsController(ProposalService proposals, ExecutionService execution, AuditService audit) {
        this.proposals = proposals;
        this.execution = execution;
        this.audit = audit;
    }

    @GetMapping
    public Flux<ActionProposal> list(@RequestParam(defaultValue = "20") int limit, @AuthenticationPrincipal CryptobotPrincipal principal) {
        return proposals.listForOwner(Principals.require(principal).userId(), limit);
    }

    @GetMapping("/{proposalId}")
    public Mono<ControlPlaneDtos.ProposalView> get(@PathVariable UUID proposalId, @AuthenticationPrincipal CryptobotPrincipal principal) {
        CryptobotPrincipal p = Principals.require(principal);
        return proposals.require(p.userId(), proposalId)
                .flatMap(pr -> audit.forProposal(pr.id()).collectList().map(ev -> new ControlPlaneDtos.ProposalView(pr, ev)));
    }

    @GetMapping("/{proposalId}/audit")
    public Flux<AuditEvent> auditTrail(@PathVariable UUID proposalId, @AuthenticationPrincipal CryptobotPrincipal principal) {
        CryptobotPrincipal p = Principals.require(principal);
        return proposals.require(p.userId(), proposalId).flatMapMany(pr -> audit.forProposal(pr.id()));
    }

    @PostMapping(path = "/{proposalId}/approve")
    public Mono<ActionProposal> approve(@PathVariable UUID proposalId, @RequestBody(required = false) ControlPlaneDtos.DecisionRequest body, @AuthenticationPrincipal CryptobotPrincipal principal) {
        CryptobotPrincipal p = Principals.require(principal);
        return proposals.approve(p.userId(), proposalId, Principals.actor(p), body == null ? null : body.note());
    }

    @PostMapping(path = "/{proposalId}/reject")
    public Mono<ActionProposal> reject(@PathVariable UUID proposalId, @RequestBody(required = false) ControlPlaneDtos.DecisionRequest body, @AuthenticationPrincipal CryptobotPrincipal principal) {
        CryptobotPrincipal p = Principals.require(principal);
        return proposals.reject(p.userId(), proposalId, Principals.actor(p), body == null ? null : body.note());
    }

    /** Second, explicit click. Only APPROVED + executable + devnet. Everything else is a 409 with the reason. */
    @PostMapping(path = "/{proposalId}/execute")
    public Mono<ActionProposal> execute(@PathVariable UUID proposalId, @AuthenticationPrincipal CryptobotPrincipal principal) {
        CryptobotPrincipal p = Principals.require(principal);
        return execution.execute(p.userId(), proposalId, Principals.actor(p));
    }
}
