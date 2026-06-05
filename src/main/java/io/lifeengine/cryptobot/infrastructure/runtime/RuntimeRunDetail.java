package io.lifeengine.cryptobot.infrastructure.runtime;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Lean slice of {@code RunDetailView} returned by {@code GET /api/runtime/runs/{runId}}. We mirror
 * only the fields the cryptobot reconciliation actually consumes; unknown fields are ignored so
 * Runtime can add new ones without breaking us.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RuntimeRunDetail(
        UUID runId,
        String workflowId,
        String correlationId,
        String status,
        Instant startedAt,
        Instant finishedAt,
        String terminalError,
        List<AgentStage> agentStages,
        List<LlmCall> llmCalls,
        List<RuntimeEventSlice> events) {

    public RuntimeRunDetail {
        agentStages = agentStages == null ? List.of() : List.copyOf(agentStages);
        llmCalls = llmCalls == null ? List.of() : List.copyOf(llmCalls);
        events = events == null ? List.of() : List.copyOf(events);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AgentStage(
            String stageId,
            String stageType,
            String status,
            String output,
            String error) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record LlmCall(String stageId, String agentId, String model) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RuntimeEventSlice(String type, Map<String, String> attributes) {
        public RuntimeEventSlice {
            attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
        }
    }
}
