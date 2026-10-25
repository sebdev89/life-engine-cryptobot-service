package io.lifeengine.cryptobot.application.pilot;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.lifeengine.cryptobot.adapters.jupiter.JupiterSwapClient;
import io.lifeengine.cryptobot.adapters.marketdata.MarketDataProperties;
import io.lifeengine.cryptobot.application.controlplane.PolicyEngine;
import io.lifeengine.cryptobot.application.oracle.PriceOracleService;
import io.lifeengine.cryptobot.core.oracle.OracleConsensus;
import io.lifeengine.cryptobot.integration.signer.SignerClient;
import io.lifeengine.cryptobot.integration.signer.SignerProperties;
import io.lifeengine.cryptobot.integration.validator.ValidatorClient;
import io.lifeengine.cryptobot.solana.rpc.SolanaRpcClient;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/** KAN-1249 pilot wiring. The beans exist always; {@code cryptobot.swaps.enabled=false} (default) refuses every call. */
@Configuration
public class SwapsConfiguration {

    private static final Logger log = LoggerFactory.getLogger(SwapsConfiguration.class);

    @Bean
    JupiterSwapClient jupiterSwapClient(WebClient.Builder builder, MarketDataProperties marketData, ObjectMapper json) {
        return new JupiterSwapClient(builder, marketData, json);
    }

    /** The shared signer, or the pilot's dedicated one when {@code cryptobot.swaps.signer.base-url} is set. */
    @Bean
    SwapsSigner swapsSigner(SignerClient shared, SwapsSignerProperties signer, WebClient.Builder builder) {
        if (!signer.dedicated()) {
            log.info("swaps_signer shared");
            return new SwapsSigner(shared, false);
        }
        log.info("swaps_signer dedicated base_url={}", signer.baseUrl());
        return new SwapsSigner(new SignerClient(builder, new SignerProperties(true, signer.baseUrl(), signer.token(), signer.timeout())), true);
    }

    @Bean
    SwapOrderService swapOrderService(SwapsProperties props, SwapOrderRepository repo, JupiterSwapClient jupiter, SolanaRpcClient rpc,
            SwapsSigner signer, MeterRegistry meters) {
        return new SwapOrderService(props, repo, jupiter, rpc, signer.client(), meters, Clock.systemUTC());
    }

    /** The SOL price the policy values a swap at: the oracle's accepted consensus, or nothing (then the policy denies). */
    @Bean
    SwapExecutor swapExecutor(SwapsProperties props, SwapOrderRepository repo, SwapOrderService orders, JupiterSwapClient jupiter, SolanaRpcClient rpc,
            ValidatorClient validator, SwapsSigner signer, PolicyEngine policy, PriceOracleService oracle, MeterRegistry meters) {
        SwapExecutor.SolPrice sol = () -> oracle.read(List.of("SOL"))
                .map(r -> r.of("SOL").filter(OracleConsensus::accepted).map(c -> new SwapExecutor.Price(c.priceUsd(), c.asOf())))
                .onErrorResume(ex -> Mono.just(Optional.empty()));
        return new SwapExecutor(props, repo, orders, jupiter, rpc, validator, signer.client(), policy, sol, meters, Clock.systemUTC());
    }

    @Bean
    SwapReconcileJob swapReconcileJob(SwapsProperties props, SwapOrderRepository repo, SwapExecutor executor) {
        return new SwapReconcileJob(props, repo, executor);
    }
}
