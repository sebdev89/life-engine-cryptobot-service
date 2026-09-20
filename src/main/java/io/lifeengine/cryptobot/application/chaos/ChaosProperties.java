package io.lifeengine.cryptobot.application.chaos;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * KAN-571 — fault injection for the demo stack, and nothing else. {@code enabled=false} (the
 * default, and what UAT/PROD run) means no chaos bean exists at all: the production
 * {@code SolanaRpcClient} is the only one in the context and there is no {@code /demo} endpoint.
 * {@code docker-compose.demo.yml} sets {@code CRYPTOBOT_CHAOS_ENABLED=true}.
 *
 * @param enabled whether the chaos decorator and {@code /api/cryptobot/demo/chaos} exist
 * @param broadcast the mode armed at startup ({@code uncertain | rpc-down | confirm-timeout}, or
 *     empty) — env {@code CRYPTOBOT_CHAOS_BROADCAST}; the endpoint arms/disarms at runtime
 * @param shots how many faulted calls the startup mode covers ({@code -1} = until disarmed)
 */
@ConfigurationProperties(prefix = "cryptobot.chaos")
public record ChaosProperties(boolean enabled, String broadcast, Integer shots) {

    public ChaosProperties {
        broadcast = broadcast == null ? "" : broadcast.trim();
        shots = shots == null ? 1 : shots;
    }
}
