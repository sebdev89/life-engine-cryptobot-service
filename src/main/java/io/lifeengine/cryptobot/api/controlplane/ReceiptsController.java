package io.lifeengine.cryptobot.api.controlplane;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import io.lifeengine.cryptobot.application.controlplane.ProposalService;
import io.lifeengine.cryptobot.application.controlplane.WalletService;
import io.lifeengine.cryptobot.application.receipt.AnchorService;
import io.lifeengine.cryptobot.application.receipt.ReceiptService;
import io.lifeengine.cryptobot.core.receipts.IntelligenceReceipt;
import io.lifeengine.cryptobot.core.receipts.ReceiptCanonicalizer;
import io.lifeengine.cryptobot.core.receipts.ReceiptEdge;
import io.lifeengine.cryptobot.security.CryptobotPrincipal;
import java.util.List;
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

/**
 * Read side of the receipts (KAN-391, the minimum of Endgame §15): one receipt with its edges,
 * {@code verify}, the receipts of one proposal, and the public key so anyone can check a
 * signature offline. Lineage walks (ancestors/descendants) are the DAG issue, not this one.
 * Everything is owner-scoped through the JWT principal; a receipt of another owner is a 404.
 */
@RestController
@RequestMapping(path = "/api/cryptobot", produces = "application/json")
public class ReceiptsController {

    /** A receipt as the API returns it: the stored record plus its edges in both directions. */
    public record ReceiptView(IntelligenceReceipt receipt, List<ReceiptEdge> parents, List<ReceiptEdge> children) {}

    /** How to verify a receipt without this service: the key and the three formulas. */
    public record SigningKeyView(String keyId, String alg, String publicKeyHex, String hashDomain, String signatureDomain, String canonicalization) {}

    private final ReceiptService receipts;
    private final AnchorService anchors;
    private final ProposalService proposals;
    private final WalletService wallets;

    public ReceiptsController(ReceiptService receipts, AnchorService anchors, ProposalService proposals, WalletService wallets) {
        this.receipts = receipts;
        this.anchors = anchors;
        this.proposals = proposals;
        this.wallets = wallets;
    }

    @GetMapping("/receipts/signing-key")
    public SigningKeyView signingKey() {
        return new SigningKeyView(receipts.signingKey().keyId(), "ed25519", receipts.signingKey().publicKeyHex(),
                ReceiptCanonicalizer.HASH_DOMAIN, ReceiptCanonicalizer.SIGNATURE_DOMAIN, "RFC 8785 (JCS)");
    }

    @GetMapping("/receipts/{receiptHash}")
    public Mono<ReceiptView> get(@PathVariable String receiptHash, @AuthenticationPrincipal CryptobotPrincipal principal) {
        CryptobotPrincipal p = Principals.require(principal);
        return receipts.require(p.userId(), receiptHash)
                .flatMap(r -> Mono.zip(receipts.parentsOf(r.receiptHash()).collectList(), receipts.childrenOf(r.receiptHash()).collectList())
                        .map(t -> new ReceiptView(r, t.getT1(), t.getT2())));
    }

    /**
     * The receipt's own checks (hash, body, signature, parents — {@link ReceiptService.Verification},
     * unwrapped so the fields and {@code valid} keep their names) plus its Merkle inclusion in the
     * anchoring batch (KAN-394): {@code anchor.proofValid} folds the stored proof back to the root.
     */
    public record ReceiptVerification(@JsonUnwrapped ReceiptService.Verification receipt, AnchorService.Inclusion anchor) {}

    @PostMapping("/receipts/{receiptHash}/verify")
    public Mono<ReceiptVerification> verify(@PathVariable String receiptHash, @AuthenticationPrincipal CryptobotPrincipal principal) {
        CryptobotPrincipal p = Principals.require(principal);
        return receipts.require(p.userId(), receiptHash)
                .flatMap(r -> Mono.zip(receipts.verify(r), anchors.inclusion(r)).map(t -> new ReceiptVerification(t.getT1(), t.getT2())));
    }

    /** The receipts a proposal left behind, oldest first: STRATEGY → RISK_DECISION → SIMULATION → EXECUTION. */
    @GetMapping("/proposals/{proposalId}/receipts")
    public Flux<IntelligenceReceipt> forProposal(@PathVariable UUID proposalId, @AuthenticationPrincipal CryptobotPrincipal principal) {
        CryptobotPrincipal p = Principals.require(principal);
        return proposals.require(p.userId(), proposalId).flatMapMany(pr -> receipts.forProposal(pr.id()));
    }

    /** The wallet's receipts, newest first (WALLET_SNAPSHOT, RISK_DECISION, HUMAN_IDEA, MARKET_ANALYSIS and the proposals'). */
    @GetMapping("/wallets/{walletId}/receipts")
    public Flux<IntelligenceReceipt> forWallet(@PathVariable UUID walletId, @RequestParam(defaultValue = "50") int limit,
            @AuthenticationPrincipal CryptobotPrincipal principal) {
        CryptobotPrincipal p = Principals.require(principal);
        return wallets.require(p.userId(), walletId).flatMapMany(w -> receipts.forWallet(w.id(), limit));
    }

    /**
     * KAN-597 (TAE fase 2, mandato §28 gap G15/G7): the receipt in the request body, not looked up
     * by hash — "anyone can check a signature offline" (class javadoc) taken literally: a caller
     * that only has what a receipt handed them (its body, its signature, optionally the canonical
     * bytes and the anchor) can verify it without ever having stored anything with this service.
     * {@code body} is required; everything else is optional (see {@link
     * ControlPlaneDtos.VerifyReceiptRequest}). No owner scoping on the candidate itself — there is
     * no stored row to own — but the caller must still authenticate, same as every other {@code
     * /api/cryptobot/**} route.
     */
    @PostMapping(path = "/receipts/verify", consumes = "application/json")
    public Mono<ReceiptVerification> verifyByBody(@RequestBody ControlPlaneDtos.VerifyReceiptRequest req, @AuthenticationPrincipal CryptobotPrincipal principal) {
        Principals.require(principal);
        IntelligenceReceipt candidate = receipts.reconstruct(req.body(), req.canonical(), req.signature(), req.keyId(), req.anchor());
        return Mono.zip(receipts.verify(candidate), anchors.inclusion(candidate)).map(t -> new ReceiptVerification(t.getT1(), t.getT2()));
    }
}
