package io.lifeengine.cryptobot.trading.advisor;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** One turn of the wallet chat. Assistant turns keep the agent's structured JSON alongside the prose. */
public record AdvisorMessage(
        UUID id,
        UUID walletId,
        UUID ownerUserId,
        String role,
        String content,
        Map<String, Object> structured,
        UUID runtimeRunId,
        Instant createdAt) {

    public AdvisorMessage {
        structured = structured == null ? Map.of() : Map.copyOf(structured);
    }
}
