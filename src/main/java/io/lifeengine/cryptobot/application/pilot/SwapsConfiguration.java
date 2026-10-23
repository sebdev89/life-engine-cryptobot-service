package io.lifeengine.cryptobot.application.pilot;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.lifeengine.cryptobot.adapters.jupiter.JupiterSwapClient;
import io.lifeengine.cryptobot.adapters.marketdata.MarketDataProperties;
import io.lifeengine.cryptobot.application.controlplane.PolicyEngine;
import io.lifeengine.cryptobot.application.oracle.PriceOracleService;
import io.lifeengine.cryptobot.core.oracle.OracleConsensus;
import io.lifeengine.cryptobot.integration.signer.SignerClient;
import io.lifeengine.cryptobot.integration.validator.ValidatorClient;
import io.lifeengine.cryptobot.solana.rpc.SolanaRpcClient;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

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

    /** The SOL price the policy values a swap at: the oracle's accepted consensus, or nothing (then the policy denies). */
    @Bean
    SwapExecutor swapExecutor(SwapsProperties props, SwapOrderRepository repo, SwapOrderService orders, JupiterSwapClient jupiter, SolanaRpcClient rpc,
            ValidatorClient validator, SignerClient signer, PolicyEngine policy, PriceOracleService oracle, MeterRegistry meters) {
        SwapExecutor.SolPrice sol = () -> oracle.read(List.of("SOL"))
                .map(r -> r.of("SOL").filter(OracleConsensus::accepted).map(c -> new SwapExecutor.Price(c.priceUsd(), c.asOf())))
                .onErrorResume(ex -> Mono.just(Optional.empty()));
        return new SwapExecutor(props, repo, orders, jupiter, rpc, validator, signer, policy, sol, meters, Clock.systemUTC());
    }

    @Bean
    SwapReconcileJob swapReconcileJob(SwapsProperties props, SwapOrderRepository repo, SwapExecutor executor) {
        return new SwapReconcileJob(props, repo, executor);
    }
}
