package io.lifeengine.cryptobot;

import io.lifeengine.cryptobot.application.MonitoringProperties;
import io.lifeengine.cryptobot.infrastructure.runtime.RuntimeClientProperties;
import io.lifeengine.cryptobot.infrastructure.snapshot.SnapshotProviderProperties;
import io.lifeengine.cryptobot.security.CryptobotJwksProperties;
import io.lifeengine.cryptobot.security.CryptobotJwtProperties;
import io.lifeengine.cryptobot.security.CryptobotRuntimeSecurityProperties;
import io.lifeengine.cryptobot.security.CryptobotSecurityProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * Entry point for the cryptobot-service vertical. Phase-1 happy path: accepts a market-review
 * request, computes a deterministic snapshot + signal, then triggers
 * {@code crypto.market-review.v1} on life-engine-runtime over HTTP.
 *
 * <p>No persistence. No live exchange credentials. Live order execution is intentionally absent —
 * any attempt to wire it must go through a new explicit ADR.
 */
@SpringBootApplication
@EnableConfigurationProperties({
    RuntimeClientProperties.class,
    SnapshotProviderProperties.class,
    CryptobotSecurityProperties.class,
    CryptobotJwtProperties.class,
    CryptobotJwksProperties.class,
    CryptobotRuntimeSecurityProperties.class,
    MonitoringProperties.class,
    io.lifeengine.cryptobot.adapters.solana.SolanaRpcProperties.class,
    io.lifeengine.cryptobot.adapters.marketdata.MarketDataProperties.class,
    io.lifeengine.cryptobot.application.controlplane.RiskRulesProperties.class,
    io.lifeengine.cryptobot.application.controlplane.PolicyProperties.class,
    io.lifeengine.cryptobot.application.controlplane.AuthorizationProperties.class,
    io.lifeengine.cryptobot.integration.signer.SignerProperties.class,
    io.lifeengine.cryptobot.integration.validator.ValidatorProperties.class,
    io.lifeengine.cryptobot.application.controlplane.TimelockProperties.class,
    io.lifeengine.cryptobot.integration.lifeengine.AdvisorProperties.class,
    io.lifeengine.cryptobot.infrastructure.solana.SolanaMarketProperties.class,
    io.lifeengine.cryptobot.adapters.quotes.QuotesProperties.class,
    io.lifeengine.cryptobot.application.reliability.ReliabilityProperties.class,
    io.lifeengine.cryptobot.application.receipt.ReceiptProperties.class
})
public class CryptobotServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(CryptobotServiceApplication.class, args);
    }
}
