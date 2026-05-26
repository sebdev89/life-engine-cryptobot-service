package io.lifeengine.cryptobot.infrastructure.runtime;

import java.util.Map;

/** Mirror of {@code io.lifeengine.runtime.api.StartRunRequest}; copy-not-reuse to avoid runtime coupling. */
public record RuntimeStartRunPayload(
        String workflowId, String input, String correlationId, Map<String, Object> metadata) {

    public RuntimeStartRunPayload {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
