package io.lifeengine.cryptobot.proofofvalue;

import io.lifeengine.cryptobot.proofofvalue.ProofOfValueDtos.IdentityRequest;
import io.lifeengine.cryptobot.proofofvalue.ProofOfValueDtos.IdentityView;
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
 * Proof of Value V1 (KAN-818) — base {@code /api/cryptobot}, JWT like the receipts.
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

    public ProofOfValueController(IdentityService identities, ValueEventService events) {
        this.identities = identities;
        this.events = events;
    }

    /** 201 when created, 200 when the id already existed (the stored identity is returned unchanged). */
    @PostMapping(path = "/identities", consumes = "application/json")
    public Mono<ResponseEntity<IdentityView>> createIdentity(@Valid @RequestBody IdentityRequest req, @AuthenticationPrincipal CryptobotPrincipal principal) {
        return identities.create(require(principal).userId(), req)
                .map(c -> ResponseEntity.status(c.created() ? HttpStatus.CREATED : HttpStatus.OK).body(IdentityView.of(c.identity())));
    }

    @GetMapping("/identities")
    public Flux<IdentityView> identities(@AuthenticationPrincipal CryptobotPrincipal principal) {
        return identities.list(require(principal).userId()).map(IdentityView::of);
    }

    @GetMapping("/identities/{id}")
    public Mono<IdentityView> identity(@PathVariable String id, @AuthenticationPrincipal CryptobotPrincipal principal) {
        return identities.require(require(principal).userId(), id).map(IdentityView::of);
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
