package io.lifeengine.cryptobot.api.controlplane;

import io.lifeengine.cryptobot.application.controlplane.ProposalService;
import io.lifeengine.cryptobot.application.controlplane.Receipts;
import io.lifeengine.cryptobot.application.receipt.LineageService;
import io.lifeengine.cryptobot.infrastructure.persistence.controlplane.LineageRepository.Direction;
import io.lifeengine.cryptobot.security.CryptobotPrincipal;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * The lineage API of Endgame §15 (KAN-393):
 *
 * <pre>
 *   GET /api/cryptobot/receipts/{hash}/lineage?direction=ancestors|descendants|both&amp;depth=N
 *   GET /api/cryptobot/receipts/{hash}/ancestors?depth=N          (alias, direction fixed)
 *   GET /api/cryptobot/receipts/{hash}/descendants?depth=N        (alias, direction fixed)
 *   GET /api/cryptobot/receipts/{hash}/parents                    direct parents with the edge role
 *   GET /api/cryptobot/receipts/{hash}/children                   direct children with the edge role
 *   GET /api/cryptobot/receipts/{hash}/reused-by                  children whose edge is REUSES
 *   GET /api/cryptobot/proposals/{id}/lineage?direction=…&amp;depth=N   the DAG the UI draws
 * </pre>
 *
 * Owner-scoped through the JWT principal (a receipt or proposal of another owner is a 404); the
 * tenant of the walk is derived server-side from the same principal. {@code depth} is capped at
 * {@code 64}; the default is {@code 16}.
 */
@RestController
@RequestMapping(path = "/api/cryptobot", produces = "application/json")
public class LineageController {

    private final LineageService lineage;
    private final ProposalService proposals;

    public LineageController(LineageService lineage, ProposalService proposals) {
        this.lineage = lineage;
        this.proposals = proposals;
    }

    @GetMapping("/receipts/{receiptHash}/lineage")
    public Mono<LineageService.Graph> lineage(@PathVariable String receiptHash, @RequestParam(required = false) String direction,
            @RequestParam(defaultValue = "" + LineageService.DEFAULT_DEPTH) int depth, @AuthenticationPrincipal CryptobotPrincipal principal) {
        CryptobotPrincipal p = Principals.require(principal);
        return lineage.lineageOf(p.userId(), receiptHash, LineageService.direction(direction), depth);
    }

    @GetMapping("/receipts/{receiptHash}/ancestors")
    public Mono<LineageService.Graph> ancestors(@PathVariable String receiptHash, @RequestParam(defaultValue = "" + LineageService.DEFAULT_DEPTH) int depth,
            @AuthenticationPrincipal CryptobotPrincipal principal) {
        CryptobotPrincipal p = Principals.require(principal);
        return lineage.lineageOf(p.userId(), receiptHash, Direction.ANCESTORS, depth);
    }

    @GetMapping("/receipts/{receiptHash}/descendants")
    public Mono<LineageService.Graph> descendants(@PathVariable String receiptHash, @RequestParam(defaultValue = "" + LineageService.DEFAULT_DEPTH) int depth,
            @AuthenticationPrincipal CryptobotPrincipal principal) {
        CryptobotPrincipal p = Principals.require(principal);
        return lineage.lineageOf(p.userId(), receiptHash, Direction.DESCENDANTS, depth);
    }

    @GetMapping("/receipts/{receiptHash}/parents")
    public Flux<LineageService.Neighbour> parents(@PathVariable String receiptHash, @AuthenticationPrincipal CryptobotPrincipal principal) {
        CryptobotPrincipal p = Principals.require(principal);
        return lineage.parents(p.userId(), receiptHash);
    }

    @GetMapping("/receipts/{receiptHash}/children")
    public Flux<LineageService.Neighbour> children(@PathVariable String receiptHash, @AuthenticationPrincipal CryptobotPrincipal principal) {
        CryptobotPrincipal p = Principals.require(principal);
        return lineage.children(p.userId(), receiptHash);
    }

    @GetMapping("/receipts/{receiptHash}/reused-by")
    public Flux<LineageService.Neighbour> reusedBy(@PathVariable String receiptHash, @AuthenticationPrincipal CryptobotPrincipal principal) {
        CryptobotPrincipal p = Principals.require(principal);
        return lineage.reusedBy(p.userId(), receiptHash);
    }

    /** The DAG of one proposal: its receipts at depth 0 and (by default) everything they descend from. */
    @GetMapping("/proposals/{proposalId}/lineage")
    public Mono<LineageService.Graph> proposalLineage(@PathVariable UUID proposalId, @RequestParam(required = false) String direction,
            @RequestParam(defaultValue = "" + LineageService.DEFAULT_DEPTH) int depth, @AuthenticationPrincipal CryptobotPrincipal principal) {
        CryptobotPrincipal p = Principals.require(principal);
        Direction dir = LineageService.direction(direction);
        return proposals.require(p.userId(), proposalId)
                .flatMap(pr -> lineage.lineageOfProposal(pr.id(), Receipts.tenantOf(pr.ownerUserId()), dir, depth));
    }
}
