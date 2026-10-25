package io.lifeengine.cryptobot.health;

import io.lifeengine.cryptobot.application.MarketSnapshotProvider;
import io.lifeengine.cryptobot.infrastructure.runtime.RuntimeClient;
import io.lifeengine.cryptobot.observability.BuildIdentityJsonProvider;
import java.time.Clock;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/cryptobot")
public class CryptobotHealthController {

    private final MarketSnapshotProvider snapshotProvider;
    private final RuntimeClient runtimeClient;
    private final String buildVersion;
    private final Clock clock = Clock.systemUTC();

    public CryptobotHealthController(
            MarketSnapshotProvider snapshotProvider,
            RuntimeClient runtimeClient,
            @Value("${spring.application.name:cryptobot-service}") String buildVersion) {
        this.snapshotProvider = snapshotProvider;
        this.runtimeClient = runtimeClient;
        this.buildVersion = buildVersion;
    }

    @GetMapping("/health")
    public HealthResponse health() {
        return new HealthResponse(
                "UP",
                snapshotProvider.id(),
                runtimeClient.runtimeBaseUrl(),
                runtimeClient.runtimeWorkflowId(),
                buildVersion,
                BuildIdentityJsonProvider.current().version(),
                BuildIdentityJsonProvider.current().commit(),
                Instant.now(clock));
    }

    public record HealthResponse(
            String status,
            String snapshotProvider,
            String runtimeBaseUrl,
            String runtimeWorkflowId,
            String buildVersion,
            /* KAN-1359: the same version and short commit every log line carries; "unknown" when the build has none */
            String version,
            String commit,
            Instant observedAt) {}
}
