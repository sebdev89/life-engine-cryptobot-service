package io.lifeengine.cryptobot.infrastructure.runtime;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.UUID;

/** Minimal slice of {@code RunResponse} that we care about — just the run id. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RuntimeStartRunResponse(UUID runId, String workflowId, String correlationId, String status) {}
