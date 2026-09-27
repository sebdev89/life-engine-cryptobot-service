package io.lifeengine.cryptobot.api.controlplane;

import io.lifeengine.cryptobot.application.controlplane.ExecutionService;
import io.lifeengine.cryptobot.application.controlplane.ProposalService;
import io.lifeengine.cryptobot.application.controlplane.WalletService;
import io.lifeengine.cryptobot.core.execution.ActionProposal;
import io.lifeengine.cryptobot.core.intent.IntentHash;
import io.lifeengine.cryptobot.security.CryptobotPrincipal;
import io.lifeengine.cryptobot.trading.strategy.RebalanceIntent;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import java.util.UUID;
import reactor.core.publisher.Mono;

/**
 * (TAE fase 2, mandato §28, gap G7): the additive entry point of the mandate — {@code
 * ExecutionIntent v1} in the body instead of {@code walletId} in the path, and {@code /intents/{id}
 * /approve|execute} as plain aliases of {@code /proposals/{id}/approve|execute}. Every call here
 * delegates into the exact same {@link ProposalService}/{@link ExecutionService} methods {@link
 * WalletsController} and {@link ProposalsController} already use — same validation, same
 * persistence, same audit trail, same receipts. {@code /wallets/**} and {@code /proposals/**} are
 * untouched (see {@code RouteContractTest}); this controller only adds routes, never removes or
 * renames one.
 */
@RestController
@RequestMapping(path = "/api/cryptobot/intents", produces = "application/json")
public class IntentsController {

    private final WalletService wallets;
    private final ProposalService proposals;
    private final ExecutionService execution;

    public IntentsController(WalletService wallets, ProposalService proposals, ExecutionService execution) {
        this.wallets = wallets;
        this.proposals = proposals;
        this.execution = execution;
    }

    /**
     * {@code executionId == proposal.id()} in the response (see {@link ControlPlaneDtos.IntentView}):
     * "execution" and "proposal" name the same resource from TAE phase 2 on, by mandate — never a
     * second row, never a different id.
     */
    @PostMapping(consumes = "application/json")
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<ControlPlaneDtos.IntentView> create(@RequestBody ControlPlaneDtos.ExecutionIntentV1 req, @AuthenticationPrincipal CryptobotPrincipal principal) {
        CryptobotPrincipal p = Principals.require(principal);
        if (req.walletId() == null) {
            return Mono.error(new io.lifeengine.cryptobot.application.controlplane.ControlPlaneExceptions.InvalidRequest("MISSING_WALLET", "walletId is required"));
        }
        if (req.kind() != null && !"REBALANCE".equalsIgnoreCase(req.kind())) {
            return Mono.error(new io.lifeengine.cryptobot.application.controlplane.ControlPlaneExceptions.InvalidRequest("UNSUPPORTED_KIND", "Only REBALANCE proposals exist in this version"));
        }
        if (req.targetWeights() == null || req.targetWeights().isEmpty()) {
            return Mono.error(new io.lifeengine.cryptobot.application.controlplane.ControlPlaneExceptions.InvalidRequest("MISSING_TARGETS", "targetWeights is required, e.g. {\"SOL\": 50}"));
        }
        return wallets.require(p.userId(), req.walletId())
                .flatMap(w -> proposals.createRebalance(w, Principals.actor(p),
                        new RebalanceIntent(req.targetWeights(), req.counterAsset()), req.reasoningSummary(), req.runtimeRunId()))
                .map(ControlPlaneDtos.IntentView::of);
    }

    /** Alias of {@link ProposalsController#approve}: same {@link ProposalService#approve}, same {@link ActionProposal} back. */
    @PostMapping(path = "/{id}/approve")
    public Mono<ActionProposal> approve(@PathVariable UUID id, @RequestBody(required = false) ControlPlaneDtos.DecisionRequest body,
            @AuthenticationPrincipal CryptobotPrincipal principal) {
        CryptobotPrincipal p = Principals.require(principal);
        return proposals.approve(p.userId(), id, Principals.actor(p), body == null ? null : body.note());
    }

    /** Alias of {@link ProposalsController#execute}: same idempotency-key parsing, same {@link ExecutionService#execute}. */
    @PostMapping(path = "/{id}/execute")
    public Mono<ActionProposal> execute(@PathVariable UUID id,
            @RequestHeader(name = ProposalsController.IDEMPOTENCY_KEY, required = false) String idempotencyKey,
            @RequestBody(required = false) ControlPlaneDtos.ExecuteRequest body,
            @AuthenticationPrincipal CryptobotPrincipal principal) {
        CryptobotPrincipal p = Principals.require(principal);
        String raw = idempotencyKey != null && !idempotencyKey.isBlank() ? idempotencyKey.trim() : body == null ? null : body.operationId();
        UUID operationId;
        String intentHash = null;
        if (raw == null || raw.isBlank()) {
            operationId = UUID.randomUUID();
        } else {
            try {
                intentHash = ProposalsController.intentHashOf(raw.trim());
                operationId = intentHash != null ? IntentHash.parse(intentHash).toOperationId() : UUID.fromString(raw.trim());
            } catch (IllegalArgumentException ex) {
                return Mono.error(new io.lifeengine.cryptobot.application.controlplane.ControlPlaneExceptions.InvalidRequest("INVALID_OPERATION_ID",
                        "Idempotency-Key / operationId must be a UUID or an intent hash sha256:<64 hex>"));
            }
        }
        return execution.execute(p.userId(), id, Principals.actor(p), operationId, intentHash);
    }
}
