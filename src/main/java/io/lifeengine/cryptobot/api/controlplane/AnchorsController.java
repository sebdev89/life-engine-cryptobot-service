package io.lifeengine.cryptobot.api.controlplane;

import io.lifeengine.cryptobot.application.receipt.AnchorService;
import io.lifeengine.cryptobot.core.receipts.ReceiptAnchor;
import io.lifeengine.cryptobot.security.CryptobotPrincipal;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Anchoring batches (KAN-394, Endgame §11 / §15). A batch is not tenant data — it is a root, a
 * memo and a transaction — so listing and verifying one is open to any operator; the receipts
 * of a batch a caller sees are only the caller's own. {@code POST /anchors} (open + submit a
 * batch) is an admin action, gated in {@code CryptobotSecurityConfig}.
 */
@RestController
@RequestMapping(path = "/api/cryptobot", produces = "application/json")
public class AnchorsController {

    /** A batch as the API returns it, with the explorer link a judge clicks. */
    public record AnchorView(ReceiptAnchor anchor, String explorerUrl) {
        static AnchorView of(ReceiptAnchor a) {
            return new AnchorView(a, AnchorService.explorerUrl(a.chain(), a.tx()));
        }
    }

    /** One batch plus the caller's receipts in it (hash + Merkle siblings). */
    public record AnchorDetail(ReceiptAnchor anchor, String explorerUrl, List<ReceiptAnchor.Member> myReceipts) {}

    private final AnchorService anchors;

    public AnchorsController(AnchorService anchors) {
        this.anchors = anchors;
    }

    /** Admin/cron: settle in-flight batches, retry failed ones, open one for the receipts waiting. {@code wait=true} polls for finality. */
    @PostMapping("/anchors")
    public Mono<AnchorService.SweepResult> sweep(@RequestParam(defaultValue = "false") boolean wait, @AuthenticationPrincipal CryptobotPrincipal principal) {
        Principals.require(principal);
        return anchors.sweep(wait);
    }

    @GetMapping("/anchors")
    public Flux<AnchorView> recent(@RequestParam(defaultValue = "20") int limit, @AuthenticationPrincipal CryptobotPrincipal principal) {
        Principals.require(principal);
        return anchors.recent(limit).map(AnchorView::of);
    }

    @GetMapping("/anchors/{root}")
    public Mono<AnchorDetail> get(@PathVariable String root, @AuthenticationPrincipal CryptobotPrincipal principal) {
        CryptobotPrincipal p = Principals.require(principal);
        return anchors.require(root).flatMap(a -> anchors.membersOwnedBy(a.root(), p.userId()).collectList()
                .map(mine -> new AnchorDetail(a, AnchorService.explorerUrl(a.chain(), a.tx()), mine)));
    }

    /** Recomputes the root from the batch's receipts, folds every proof, parses the memo and reads the transaction back from devnet. */
    @PostMapping("/anchors/{root}/verify")
    public Mono<AnchorService.AnchorVerification> verify(@PathVariable String root, @AuthenticationPrincipal CryptobotPrincipal principal) {
        Principals.require(principal);
        return anchors.verify(root);
    }
}
