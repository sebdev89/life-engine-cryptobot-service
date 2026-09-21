package io.lifeengine.cryptobot.application.chaos;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.lifeengine.cryptobot.adapters.solana.ExecutionProperties;
import io.lifeengine.cryptobot.adapters.solana.SolanaRpcClient;
import io.lifeengine.cryptobot.adapters.solana.SolanaRpcProperties;
import io.lifeengine.cryptobot.observability.CryptobotMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * KAN-571 — wires the chaos RPC as the {@code @Primary} {@link SolanaRpcClient} only when
 * {@code cryptobot.chaos.enabled=true}. Off (default): this class contributes nothing and the
 * production client is the only bean. The startup log says so loudly either way it is on.
 */
@Configuration
@EnableConfigurationProperties(ChaosProperties.class)
@ConditionalOnProperty(prefix = "cryptobot.chaos", name = "enabled", havingValue = "true")
public class ChaosConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ChaosConfiguration.class);

    @Bean
    BroadcastChaos broadcastChaos(ChaosProperties props) {
        BroadcastChaos chaos = new BroadcastChaos();
        BroadcastChaos.Mode initial = BroadcastChaos.Mode.parse(props.broadcast());
        if (initial != null) {
            chaos.arm(initial, props.shots());
        }
        log.warn("chaos_enabled — DEMO ONLY: fault injection on sendTransaction/getSignatureStatuses/getBlockHeight; armed={} shots={}",
                initial == null ? "none" : initial.id(), initial == null ? 0 : props.shots());
        return chaos;
    }

    /** KAN-572: the demo's adversarial price. Consulted by {@code PriceOracleService} only when this bean exists. */
    @Bean
    PriceChaos priceChaos(ChaosProperties props) {
        PriceChaos chaos = new PriceChaos();
        for (PriceChaos.Override o : PriceChaos.Override.parse(props.priceOverride())) {
            chaos.arm(o);
        }
        log.warn("price_chaos_enabled — DEMO ONLY: PUT /api/cryptobot/demo/price tampers with what the oracle's sources said; armed={}", chaos.overrides());
        return chaos;
    }

    @Bean
    @Primary
    SolanaRpcClient chaosSolanaRpcClient(WebClient.Builder builder, SolanaRpcProperties properties, ObjectMapper objectMapper, CryptobotMetrics metrics,
            ExecutionProperties execution, BroadcastChaos chaos) {
        return new ChaosSolanaRpcClient(builder, properties, objectMapper, metrics, execution, chaos);
    }
}
