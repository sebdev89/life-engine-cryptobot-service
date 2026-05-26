package io.lifeengine.cryptobot.infrastructure.runtime;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("cryptobot.runtime")
public record RuntimeClientProperties(String baseUrl, String workflowId) {

    public String baseUrlNormalized() {
        if (baseUrl == null || baseUrl.isBlank()) {
            return "http://localhost:8090";
        }
        return baseUrl.replaceAll("/+$", "");
    }

    public String workflowIdNormalized() {
        return workflowId == null || workflowId.isBlank() ? "crypto.market-review.v1" : workflowId;
    }
}
