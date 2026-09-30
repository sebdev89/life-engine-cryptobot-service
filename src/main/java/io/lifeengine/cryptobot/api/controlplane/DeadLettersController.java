package io.lifeengine.cryptobot.api.controlplane;

import io.lifeengine.cryptobot.application.reliability.DeadLetterService;
import io.lifeengine.cryptobot.core.reliability.DeadLetter;
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
import reactor.core.publisher.Mono;

/**
 * the dead-letter queue, globally (admin: {@code RUNTIME_ADMIN}). The
 * per-proposal view stays at {@code GET /proposals/{id}/events}. See {@code docs/runbooks/dead-letter.md}.
 */
@RestController
@RequestMapping(path = "/api/cryptobot/dead-letters", produces = "application/json")
public class DeadLettersController {

    private final DeadLetterService deadLetters;

    public DeadLettersController(DeadLetterService deadLetters) {
        this.deadLetters = deadLetters;
    }

    public record DeadLetterPage(List<DeadLetter> deadLetters, long open, int limit, int offset) {}

    public record ResolutionRequest(String note) {}

    /**
     * {@code ?resolved=false} (default: open only; {@code all} for everything; {@code true} for
     * resolved), {@code ?source=OUTBOX|RECONCILIATION}, {@code ?proposalId=}, {@code ?limit=50&offset=0}.
     * {@code open} is the global unresolved count (= {@code cryptobot_dead_letter_open}).
     */
    @GetMapping
    public Mono<DeadLetterPage> list(@RequestParam(defaultValue = "false") String resolved,
            @RequestParam(required = false) DeadLetter.Source source,
            @RequestParam(required = false) UUID proposalId,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "0") int offset,
            @AuthenticationPrincipal CryptobotPrincipal principal) {
        Principals.require(principal);
        Boolean filter = "all".equalsIgnoreCase(resolved) ? null : Boolean.valueOf(resolved);
        return deadLetters.list(filter, source, proposalId, limit, offset).collectList()
                .zipWith(deadLetters.open(), (items, open) -> new DeadLetterPage(items, open, limit, offset));
    }

    @GetMapping("/{id}")
    public Mono<DeadLetter> get(@PathVariable UUID id, @AuthenticationPrincipal CryptobotPrincipal principal) {
        Principals.require(principal);
        return deadLetters.require(id);
    }

    /** Close by hand, with a note. A RECONCILIATION letter's proposal is settled to what the chain proves first (409 if no verdict yet). */
    @PostMapping("/{id}/resolve")
    public Mono<DeadLetterService.Resolution> resolve(@PathVariable UUID id, @RequestBody(required = false) ResolutionRequest body,
            @AuthenticationPrincipal CryptobotPrincipal principal) {
        CryptobotPrincipal p = Principals.require(principal);
        return deadLetters.resolve(id, Principals.actor(p), body == null ? null : body.note());
    }

    /** Give it back to the system: outbox event PENDING again, or the proposal reconciled now (idempotent retry under the same operationId). */
    @PostMapping("/{id}/requeue")
    public Mono<DeadLetterService.Resolution> requeue(@PathVariable UUID id, @RequestBody(required = false) ResolutionRequest body,
            @AuthenticationPrincipal CryptobotPrincipal principal) {
        CryptobotPrincipal p = Principals.require(principal);
        return deadLetters.requeue(id, Principals.actor(p), body == null ? null : body.note());
    }
}
