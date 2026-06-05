package io.lifeengine.cryptobot.application;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Config for the read-only cryptobot monitoring loop. Defaults are conservative — disabled and
 * 10-minute interval — so a fresh dev boot never auto-runs anything until the operator opts in
 * (either by flipping {@link #enabled()} or by calling {@code POST /api/cryptobot/monitoring/run-once}).
 */
@ConfigurationProperties("cryptobot.monitoring")
public record MonitoringProperties(
        Boolean enabled, String symbols, Duration interval, String workflowId) {

    public MonitoringProperties {
        if (enabled == null) enabled = Boolean.FALSE;
        if (symbols == null || symbols.isBlank()) symbols = "BTCUSDT,SOLUSDT";
        if (interval == null || interval.isZero() || interval.isNegative()) {
            interval = Duration.ofMinutes(10);
        }
        if (workflowId == null || workflowId.isBlank()) {
            workflowId = "crypto.market-review.v1";
        }
    }

    public List<String> symbolList() {
        return Arrays.stream(symbols.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(s -> s.toUpperCase(Locale.ROOT))
                .distinct()
                .toList();
    }

    public boolean enabledFlag() {
        return Boolean.TRUE.equals(enabled);
    }
}
