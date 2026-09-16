package io.lifeengine.cryptobot.observability;

import io.lifeengine.cryptobot.application.MonitoringProperties;
import io.lifeengine.cryptobot.application.controlplane.PolicyProperties;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires {@link CryptobotMetrics} on the application's {@link MeterRegistry} (KAN-425).
 *
 * <p>The {@code asset} label allow-list is the union of the policy's tradable assets, the monitoring
 * symbols and {@code cryptobot.metrics.assets} (comma-separated, optional). Anything else is
 * reported as {@code other}, which keeps the series count bounded no matter what symbol a caller
 * sends to the market review.
 */
@Configuration
class CryptobotMetricsConfig {

    @Bean
    CryptobotMetrics cryptobotMetrics(
            MeterRegistry registry,
            PolicyProperties policy,
            MonitoringProperties monitoring,
            @Value("${cryptobot.metrics.assets:}") String extraAssets) {
        Set<String> assets = new LinkedHashSet<>(policy.allowedAssets());
        assets.addAll(monitoring.symbolList());
        Arrays.stream(extraAssets.split(",")).map(String::trim).filter(s -> !s.isEmpty()).forEach(assets::add);
        return new CryptobotMetrics(registry, assets);
    }
}
