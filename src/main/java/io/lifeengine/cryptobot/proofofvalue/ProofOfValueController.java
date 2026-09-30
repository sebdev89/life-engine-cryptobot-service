package io.lifeengine.cryptobot.proofofvalue;

import io.lifeengine.cryptobot.proofofvalue.ProofOfValueDtos.IdentityRequest;
import io.lifeengine.cryptobot.proofofvalue.ProofOfValueDtos.IdentityProfileView;
import io.lifeengine.cryptobot.proofofvalue.ProofOfValueDtos.IdentitySummaryView;
import io.lifeengine.cryptobot.proofofvalue.ProofOfValueDtos.IdentityView;
import io.lifeengine.cryptobot.proofofvalue.ProofOfValueDtos.KnowledgeAssetRequest;
import io.lifeengine.cryptobot.proofofvalue.ProofOfValueDtos.KnowledgeAssetView;
import io.lifeengine.cryptobot.proofofvalue.ProofOfValueDtos.LedgerView;
import io.lifeengine.cryptobot.proofofvalue.ProofOfValueDtos.ProofView;
import io.lifeengine.cryptobot.proofofvalue.ProofOfValueDtos.ValueEventRequest;
import io.lifeengine.cryptobot.proofofvalue.ProofOfValueDtos.ValueEventView;
import io.lifeengine.cryptobot.security.CryptobotPrincipal;
import io.lifeengine.cryptobot.security.CryptobotSecurityConfig;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
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
 * Proof of Value V1 (KAN-818) and V2–V4 + V6 (KAN-819: identities with reputation, knowledge assets, compute
 * receipts inside the event, Contribution Units ledger) — base {@code /api/cryptobot}, JWT like the receipts.
 *
 * <p>Roles: every route here falls under the catch-all {@code /api/cryptobot/**} of
 * {@code CryptobotSecurityConfig} ({@code RUNTIME_OPERATOR}), the same as {@code /receipts}.
 * {@code POST /value-events?anchor=true} additionally requires {@code RUNTIME_ADMIN}, because it
 * runs the sweep of {@code POST /anchors?wait=true} (a devnet transaction), which is admin-only.
 * The tenant is the JWT subject, never a request field; another tenant's event is a 404.
 */
@RestController
@RequestMapping(path = "/api/cryptobot", produces = "application/json")
public class ProofOfValueController {

    private final IdentityService identities;
    private final ValueEventService events;
    private final KnowledgeAssetService knowledge;
    private final AttributionReadModel attribution;

    public ProofOfValueController(IdentityService identities, ValueEventService events, KnowledgeAssetService knowledge,
            AttributionReadModel attribution) {
        this.identities = identities;
        this.events = events;
        this.knowledge = knowledge;
        this.attribution = attribution;
    }

    /** 201 when created, 200 when the id already existed (the stored identity is returned unchanged). */
    @PostMapping(path = "/identities", consumes = "application/json")
    public Mono<ResponseEntity<IdentityView>> createIdentity(@Valid @RequestBody IdentityRequest req, @AuthenticationPrincipal CryptobotPrincipal principal) {
        return identities.create(require(principal).userId(), req)
                .map(c -> ResponseEntity.status(c.created() ? HttpStatus.CREATED : HttpStatus.OK).body(IdentityView.of(c.identity())));
    }

    /** KAN-819: each identity with its {@code reputation}. */
    @GetMapping("/identities")
    public Flux<IdentitySummaryView> identities(@AuthenticationPrincipal CryptobotPrincipal principal) {
        return attribution.identities(require(principal).userId());
    }

    /** KAN-819: the identity, its {@code reputation} and its {@code history} (newest first). */
    @GetMapping("/identities/{id}")
    public Mono<IdentityProfileView> identity(@PathVariable String id, @AuthenticationPrincipal CryptobotPrincipal principal) {
        return attribution.profile(require(principal).userId(), id);
    }

    /** KAN-820: 201 when registered, 200 when the id already existed (returned unchanged). 422 unknown creator or parent. */
    @PostMapping(path = "/knowledge-assets", consumes = "application/json")
    public Mono<ResponseEntity<KnowledgeAssetView>> createKnowledgeAsset(@Valid @RequestBody KnowledgeAssetRequest req,
            @AuthenticationPrincipal CryptobotPrincipal principal) {
        return knowledge.create(require(principal).userId(), req)
                .map(c -> ResponseEntity.status(c.created() ? HttpStatus.CREATED : HttpStatus.OK).body(c.asset()));
    }

    @GetMapping("/knowledge-assets")
    public Flux<KnowledgeAssetView> knowledgeAssets(@AuthenticationPrincipal CryptobotPrincipal principal) {
        return knowledge.list(require(principal).userId());
    }

    @GetMapping("/knowledge-assets/{id}")
    public Mono<KnowledgeAssetView> knowledgeAsset(@PathVariable String id, @AuthenticationPrincipal CryptobotPrincipal principal) {
        return knowledge.get(require(principal).userId(), id);
    }

    /** KAN-823: Contribution Units by {@code identity} (default), {@code asset} or {@code project}; 400 otherwise. */
    @GetMapping("/units/ledger")
    public Mono<LedgerView> ledger(@RequestParam(required = false) String groupBy, @AuthenticationPrincipal CryptobotPrincipal principal) {
        return attribution.ledger(require(principal).userId(), groupBy);
    }

    /** 201 when recorded, 200 when the same event (same canonical hash) was already recorded. 422 when the AcceptancePolicy refuses it. */
    @PostMapping(path = "/value-events", consumes = "application/json")
    public Mono<ResponseEntity<ValueEventView>> record(@Valid @RequestBody ValueEventRequest req, @RequestParam(defaultValue = "false") boolean anchor,
            @AuthenticationPrincipal CryptobotPrincipal principal) {
        CryptobotPrincipal p = require(principal);
        if (anchor && !p.authorities().contains(CryptobotSecurityConfig.AUTHORITY_ANCHOR_ADMIN)) {
            return Mono.error(new AccessDeniedException("anchor=true runs the anchoring sweep: " + CryptobotSecurityConfig.AUTHORITY_ANCHOR_ADMIN));
        }
        return events.record(p.userId(), req, anchor)
                .map(r -> ResponseEntity.status(r.created() ? HttpStatus.CREATED : HttpStatus.OK).body(r.view()));
    }

    @GetMapping("/value-events")
    public Flux<ValueEventView> list(@RequestParam(required = false) Integer limit, @AuthenticationPrincipal CryptobotPrincipal principal) {
        return events.list(require(principal).userId(), limit);
    }

    @GetMapping("/value-events/{id}")
    public Mono<ValueEventView> get(@PathVariable UUID id, @AuthenticationPrincipal CryptobotPrincipal principal) {
        return events.get(require(principal).userId(), id);
    }

    @GetMapping("/value-events/{id}/proof")
    public Mono<ProofView> proof(@PathVariable UUID id, @AuthenticationPrincipal CryptobotPrincipal principal) {
        return events.proof(require(principal).userId(), id);
    }

    private static CryptobotPrincipal require(CryptobotPrincipal principal) {
        if (principal == null || principal.userId() == null) {
            throw new IllegalStateException("Missing authenticated principal");
        }
        return principal;
    }
}
