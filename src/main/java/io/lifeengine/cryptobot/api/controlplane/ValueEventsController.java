package io.lifeengine.cryptobot.api.controlplane;

import io.lifeengine.cryptobot.application.value.ValueEventService;
import io.lifeengine.cryptobot.core.value.ValueEvent;
import io.lifeengine.cryptobot.infrastructure.persistence.controlplane.ValueEventRepository;
import io.lifeengine.cryptobot.security.CryptobotPrincipal;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Proof of Value V1 (KAN-818). The tenant is the JWT's owner, never a request field. Reads are
 * owner-scoped: another owner's event is a 404.
 */
@RestController
@RequestMapping(path = "/api/cryptobot/value-events", produces = "application/json")
public class ValueEventsController {

    public record IdentityBody(String id, ValueEvent.IdentityKind kind) {}

    public record AgentBody(String id, String ownerId, String modelRef, String version) {}

    public record ContributionBody(ValueEvent.ContributionType type, String evidenceHash, String evidenceRef) {}

    public record AcceptanceBody(IdentityBody acceptor, String method, String evidenceHash, String evidenceRef, Instant acceptedAt) {}

    /** {@code agent} is optional; when present it is the contributor and must be an AGENT identity. */
    public record RecordRequest(IdentityBody contributor, AgentBody agent, ContributionBody contribution, AcceptanceBody acceptance, Instant occurredAt,
            String nonce) {}

    private final ValueEventService service;

    public ValueEventsController(ValueEventService service) {
        this.service = service;
    }

    @PostMapping(consumes = "application/json")
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<ValueEventService.View> record(@RequestBody RecordRequest req, @AuthenticationPrincipal CryptobotPrincipal principal) {
        CryptobotPrincipal p = Principals.require(principal);
        if (req == null || req.contributor() == null || req.contribution() == null || req.acceptance() == null || req.acceptance().acceptor() == null
                || req.occurredAt() == null) {
            return Mono.error(new io.lifeengine.cryptobot.application.controlplane.ControlPlaneExceptions.InvalidRequest("INVALID_VALUE_EVENT",
                    "contributor, contribution, acceptance and occurredAt are required"));
        }
        ValueEventService.Command cmd;
        try {
            ValueEvent.Identity contributor = new ValueEvent.Identity(req.contributor().id(), req.contributor().kind());
            ValueEvent.AgentIdentity agent = req.agent() == null ? null
                    : new ValueEvent.AgentIdentity(new ValueEvent.Identity(req.agent().id(), ValueEvent.IdentityKind.AGENT), req.agent().ownerId(),
                            req.agent().modelRef(), req.agent().version());
            ValueEvent.Contribution contribution = new ValueEvent.Contribution(req.contribution().type(), req.contribution().evidenceHash(),
                    req.contribution().evidenceRef());
            AcceptanceBody a = req.acceptance();
            ValueEvent.AcceptanceProof acceptance = new ValueEvent.AcceptanceProof(new ValueEvent.Identity(a.acceptor().id(), a.acceptor().kind()),
                    a.method(), a.evidenceHash(), a.evidenceRef(), a.acceptedAt());
            cmd = new ValueEventService.Command(contributor, agent, contribution, acceptance, req.occurredAt(), req.nonce());
        } catch (IllegalArgumentException | NullPointerException ex) {
            return Mono.error(new io.lifeengine.cryptobot.application.controlplane.ControlPlaneExceptions.InvalidRequest("INVALID_VALUE_EVENT",
                    ex.getMessage()));
        }
        return service.record(p.userId(), cmd);
    }

    @GetMapping
    public Flux<ValueEventRepository.Row> list(@RequestParam(defaultValue = "50") int limit, @RequestParam(required = false) String evidenceHash,
            @AuthenticationPrincipal CryptobotPrincipal principal) {
        CryptobotPrincipal p = Principals.require(principal);
        return evidenceHash == null ? service.list(p.userId(), limit) : service.byEvidence(p.userId(), evidenceHash);
    }

    /** The minimum dashboard: totals, anchored count, by type / contributor / acceptor, the latest ten. */
    @GetMapping("/summary")
    public Mono<ValueEventService.Summary> summary(@AuthenticationPrincipal CryptobotPrincipal principal) {
        return service.summary(Principals.require(principal).userId());
    }

    @GetMapping("/{valueEventHash}")
    public Mono<ValueEventService.View> get(@PathVariable String valueEventHash, @AuthenticationPrincipal CryptobotPrincipal principal) {
        return service.get(Principals.require(principal).userId(), valueEventHash);
    }

    @PostMapping("/{valueEventHash}/verify")
    public Mono<ValueEventService.Verification> verify(@PathVariable String valueEventHash, @AuthenticationPrincipal CryptobotPrincipal principal) {
        return service.verify(Principals.require(principal).userId(), valueEventHash);
    }
}
