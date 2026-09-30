package io.lifeengine.cryptobot.proofofvalue;

import io.lifeengine.cryptobot.proofofvalue.ProofOfValueDtos.DistributionView;
import io.lifeengine.cryptobot.proofofvalue.ProofOfValueDtos.IdentityRequest;
import io.lifeengine.cryptobot.proofofvalue.ProofOfValueDtos.IdentityProfileView;
import io.lifeengine.cryptobot.proofofvalue.ProofOfValueDtos.IdentitySummaryView;
import io.lifeengine.cryptobot.proofofvalue.ProofOfValueDtos.IdentityView;
import io.lifeengine.cryptobot.proofofvalue.ProofOfValueDtos.KnowledgeAssetRequest;
import io.lifeengine.cryptobot.proofofvalue.ProofOfValueDtos.KnowledgeAssetView;
import io.lifeengine.cryptobot.proofofvalue.ProofOfValueDtos.LedgerView;
import io.lifeengine.cryptobot.proofofvalue.ProofOfValueDtos.ProofView;
import io.lifeengine.cryptobot.proofofvalue.ProofOfValueDtos.RevenueEventRequest;
import io.lifeengine.cryptobot.proofofvalue.ProofOfValueDtos.RevenueEventView;
import io.lifeengine.cryptobot.proofofvalue.ProofOfValueDtos.TreasuryView;
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
 * Proof of Value V1 and V2–V4 + V6 (identities with reputation, knowledge assets, compute
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
    private final PovRewardService rewards;
    private final PovRevenueService revenue;
    private final TreasuryService treasury;

    public ProofOfValueController(IdentityService identities, ValueEventService events, KnowledgeAssetService knowledge,
            AttributionReadModel attribution, PovRewardService rewards, PovRevenueService revenue, TreasuryService treasury) {
        this.rewards = rewards;
        this.revenue = revenue;
        this.treasury = treasury;
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

    /** each identity with its {@code reputation}. */
    @GetMapping("/identities")
    public Flux<IdentitySummaryView> identities(@AuthenticationPrincipal CryptobotPrincipal principal) {
        return attribution.identities(require(principal).userId());
    }

    /** the identity, its {@code reputation} and its {@code history} (newest first). */
    @GetMapping("/identities/{id}")
    public Mono<IdentityProfileView> identity(@PathVariable String id, @AuthenticationPrincipal CryptobotPrincipal principal) {
        return attribution.profile(require(principal).userId(), id);
    }

    /** 201 when registered, 200 when the id already existed (returned unchanged). 422 unknown creator or parent. */
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

    /** Contribution Units by {@code identity} (default), {@code asset} or {@code project}; 400 otherwise. */
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

    /**
     * (V5): the immediate reward of an ANCHORED event — one devnet SOL transfer per contributor wallet, each attested by the
     * validator and signed by the signer. {@code RUNTIME_ADMIN} (it moves funds). 201 when distributed now, 200 with the existing
     * distribution on any later call (idempotent), 409 when the event is not ANCHORED or the reward is disabled.
     * {@code ?anchor=true} also runs the sweep for the VALUE_DISTRIBUTION receipt.
     */
    @PostMapping("/value-events/{id}/distribute")
    public Mono<ResponseEntity<DistributionView>> distribute(@PathVariable UUID id, @RequestParam(defaultValue = "false") boolean anchor,
            @AuthenticationPrincipal CryptobotPrincipal principal) {
        CryptobotPrincipal p = require(principal);
        if (!p.authorities().contains(CryptobotSecurityConfig.AUTHORITY_ANCHOR_ADMIN)) {
            return Mono.error(new AccessDeniedException("distribute moves devnet funds: " + CryptobotSecurityConfig.AUTHORITY_ANCHOR_ADMIN));
        }
        return rewards.distribute(p.userId(), id, anchor)
                .map(r -> ResponseEntity.status(r.created() ? HttpStatus.CREATED : HttpStatus.OK).body(r.view()));
    }

    /** (V5): the event's distribution (payouts reconciled with the chain on read); 404 when it has none. */
    @GetMapping("/value-events/{id}/distribution")
    public Mono<DistributionView> distribution(@PathVariable UUID id, @AuthenticationPrincipal CryptobotPrincipal principal) {
        return rewards.get(require(principal).userId(), id);
    }

    /**
     * (V7): an economic result split with {@code pov/revenue-share/v1}; the contributor pool is paid with the V5 flow.
     * {@code RUNTIME_ADMIN} (it moves funds). 201 recorded now, 200 the same source with the same content (idempotent), 409 the same
     * source with other content or the reward flow disabled, 422 a linked event unknown or not ANCHORED, or a PROPOSAL source not
     * EXECUTED. {@code ?anchor=true} also runs the sweep for the REVENUE_EVENT receipt.
     */
    @PostMapping(path = "/revenue-events", consumes = "application/json")
    public Mono<ResponseEntity<RevenueEventView>> recordRevenue(@Valid @RequestBody RevenueEventRequest req, @RequestParam(defaultValue = "false") boolean anchor,
            @AuthenticationPrincipal CryptobotPrincipal principal) {
        CryptobotPrincipal p = require(principal);
        if (!p.authorities().contains(CryptobotSecurityConfig.AUTHORITY_ANCHOR_ADMIN)) {
            return Mono.error(new AccessDeniedException("a revenue event moves devnet funds: " + CryptobotSecurityConfig.AUTHORITY_ANCHOR_ADMIN));
        }
        return revenue.record(p.userId(), req, anchor)
                .map(r -> ResponseEntity.status(r.created() ? HttpStatus.CREATED : HttpStatus.OK).body(r.view()));
    }

    /** newest first; payouts reconciled with the chain on read. */
    @GetMapping("/revenue-events")
    public Flux<RevenueEventView> revenueEvents(@RequestParam(required = false) Integer limit, @AuthenticationPrincipal CryptobotPrincipal principal) {
        return revenue.list(require(principal).userId(), limit);
    }

    @GetMapping("/revenue-events/{id}")
    public Mono<RevenueEventView> revenueEvent(@PathVariable UUID id, @AuthenticationPrincipal CryptobotPrincipal principal) {
        return revenue.get(require(principal).userId(), id);
    }

    /** (V8): an identity's accounting treasury (on-chain balance, income, payouts, fee, compute cost, retained, policies, recent events). */
    @GetMapping("/treasury/{identityId}")
    public Mono<TreasuryView> treasury(@PathVariable String identityId, @AuthenticationPrincipal CryptobotPrincipal principal) {
        return treasury.get(require(principal).userId(), identityId);
    }

    private static CryptobotPrincipal require(CryptobotPrincipal principal) {
        if (principal == null || principal.userId() == null) {
            throw new IllegalStateException("Missing authenticated principal");
        }
        return principal;
    }
}
