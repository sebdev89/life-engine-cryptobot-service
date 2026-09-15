package io.lifeengine.cryptobot.integration.lifeengine;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Which Runtime workflow answers the wallet chat, and how long we wait for it. */
@ConfigurationProperties(prefix = "cryptobot.advisor")
public record AdvisorProperties(String workflowId, Duration timeout, Duration pollInterval, String locale) {

    public AdvisorProperties {
        workflowId = workflowId == null || workflowId.isBlank() ? "crypto.portfolio-advisor.v1" : workflowId.trim();
        timeout = timeout == null ? Duration.ofSeconds(90) : timeout;
        pollInterval = pollInterval == null ? Duration.ofMillis(1500) : pollInterval;
        locale = locale == null || locale.isBlank() ? "en" : locale.trim();
    }
}
