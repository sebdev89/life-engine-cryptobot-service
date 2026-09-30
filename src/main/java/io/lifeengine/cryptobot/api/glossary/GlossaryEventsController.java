package io.lifeengine.cryptobot.api.glossary;

import io.lifeengine.cryptobot.api.controlplane.ControlPlaneDtos;
import io.lifeengine.cryptobot.application.controlplane.ControlPlaneExceptions;
import io.lifeengine.cryptobot.application.glossary.GlossaryEventsService;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * {@code POST /api/cryptobot/glossary/events} — the glossary drawer of cryptobot-ui reports what
 * people open, search and copy, in batches.
 *
 * <pre>
 * POST /api/cryptobot/glossary/events
 * {"events":[{"term":"PDA","action":"open"},
 *            {"term":"blockhash","action":"search","hit":true},
 *            {"action":"search","hit":false},
 *            {"term":"slippage","action":"copy"}]}
 * → 202 {"accepted":4,"rejected":0}
 * </pre>
 *
 * Fire-and-forget from the UI's point of view: a malformed row is dropped and counted in
 * {@code rejected}, only an unreadable body or a batch over {@value GlossaryEventsService#MAX_BATCH}
 * rows is a 400. No PII travels and none is stored: the only sink is the Prometheus registry
 * (see {@link io.lifeengine.cryptobot.observability.CryptobotMetrics}). Same authority as the rest
 * of {@code /api/cryptobot/**}: the UI never talks to Prometheus or Grafana, and Runtime is not involved.
 */
@RestController
@RequestMapping(path = "/api/cryptobot/glossary", produces = "application/json")
public class GlossaryEventsController {

    private final GlossaryEventsService events;

    public GlossaryEventsController(GlossaryEventsService events) {
        this.events = events;
    }

    public record EventsBatch(List<GlossaryEventsService.GlossaryEvent> events) {}

    @PostMapping(path = "/events", consumes = "application/json")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Mono<GlossaryEventsService.Outcome> record(@RequestBody(required = false) EventsBatch body) {
        if (body == null || body.events() == null) {
            throw new ControlPlaneExceptions.InvalidRequest("INVALID_BATCH", "body must be {\"events\":[…]}");
        }
        if (body.events().size() > GlossaryEventsService.MAX_BATCH) {
            throw new ControlPlaneExceptions.InvalidRequest("BATCH_TOO_LARGE", "at most " + GlossaryEventsService.MAX_BATCH + " events per batch");
        }
        return Mono.fromSupplier(() -> events.accept(body.events()));
    }

    @ExceptionHandler(ControlPlaneExceptions.InvalidRequest.class)
    public ResponseEntity<ControlPlaneDtos.ApiError> invalid(ControlPlaneExceptions.InvalidRequest ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ControlPlaneDtos.ApiError(ex.code(), ex.getMessage()));
    }
}
