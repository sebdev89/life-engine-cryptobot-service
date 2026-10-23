package io.lifeengine.cryptobot.application.pilot;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.lifeengine.cryptobot.adapters.jupiter.JupiterSwapClient;
import io.lifeengine.cryptobot.adapters.marketdata.MarketDataProperties;
import io.lifeengine.cryptobot.integration.signer.SignerClient;
import io.lifeengine.cryptobot.solana.rpc.SolanaRpcClient;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

/** KAN-1249 pilot wiring. The beans exist always; {@code cryptobot.swaps.enabled=false} (default) refuses every call. */
@Configuration
public class SwapsConfiguration {

    @Bean
    JupiterSwapClient jupiterSwapClient(WebClient.Builder builder, MarketDataProperties marketData, ObjectMapper json) {
        return new JupiterSwapClient(builder, marketData, json);
    }

    @Bean
    SwapOrderService swapOrderService(SwapsProperties props, SwapOrderRepository repo, JupiterSwapClient jupiter, SolanaRpcClient rpc,
            SignerClient signer, MeterRegistry meters) {
        return new SwapOrderService(props, repo, jupiter, rpc, signer, meters, Clock.systemUTC());
    }
}
