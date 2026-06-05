package io.lifeengine.cryptobot.api;

import io.lifeengine.cryptobot.application.MonitoringService;
import io.lifeengine.cryptobot.security.CryptobotPrincipal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/** Manual trigger for the monitoring loop. See {@link MonitoringService} for the longer story. */
@RestController
@RequestMapping("/api/cryptobot/monitoring")
public class MonitoringController {

    private final MonitoringService monitoringService;

    public MonitoringController(MonitoringService monitoringService) {
        this.monitoringService = monitoringService;
    }

    @PostMapping(path = "/run-once", produces = "application/json")
    public Mono<MonitoringRunOnceResponse> runOnce(
            @AuthenticationPrincipal CryptobotPrincipal principal) {
        if (principal == null) {
            return Mono.error(new IllegalStateException("Missing authenticated principal"));
        }
        String requestedBy =
                principal.email() != null && !principal.email().isBlank()
                        ? principal.email()
                        : (principal.userId() != null ? principal.userId().toString() : "anonymous");
        Instant startedAt = Instant.now();
        return monitoringService
                .runOnce(principal.rawToken(), requestedBy)
                .map(
                        response ->
                                new TriggeredRun(
                                        response.symbol(),
                                        response.related().runtimeRunId(),
                                        response.marketReviewRunId()))
                .collectList()
                .map(
                        triggered ->
                                new MonitoringRunOnceResponse(
                                        startedAt,
                                        Instant.now(),
                                        monitoringService.configuredSymbols(),
                                        triggered));
    }

    public record MonitoringRunOnceResponse(
            Instant startedAt,
            Instant finishedAt,
            List<String> configuredSymbols,
            List<TriggeredRun> triggered) {}

    public record TriggeredRun(String symbol, UUID runtimeRunId, UUID marketReviewRunId) {}
}
