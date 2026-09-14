package io.lifeengine.cryptobot.application.controlplane;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The guard-rails. Every value is a hard limit enforced in code, never a hint to the model.
 * {@code executionEnabled=false} is the emergency stop: nothing is signed or broadcast.
 */
@ConfigurationProperties(prefix = "cryptobot.policy")
public record PolicyProperties(
        boolean executionEnabled,
        String executionCluster,
        BigDecimal maxTradeUsd,
        BigDecimal maxTradePctOfPortfolio,
        List<String> allowedAssets,
        Duration cooldown,
        String rebalanceVault,
        long maxLamportsPerTx,
        Duration proposalTtl) {

    public PolicyProperties {
        executionCluster = executionCluster == null || executionCluster.isBlank() ? "devnet" : executionCluster.trim();
        maxTradeUsd = maxTradeUsd == null ? new BigDecimal("500") : maxTradeUsd;
        maxTradePctOfPortfolio = maxTradePctOfPortfolio == null ? new BigDecimal("50") : maxTradePctOfPortfolio;
        allowedAssets = allowedAssets == null || allowedAssets.isEmpty() ? List.of("SOL", "USDC", "USDT") : List.copyOf(allowedAssets);
        cooldown = cooldown == null ? Duration.ofSeconds(60) : cooldown;
        rebalanceVault = rebalanceVault == null ? "" : rebalanceVault.trim();
        maxLamportsPerTx = maxLamportsPerTx <= 0 ? 2_000_000_000L : maxLamportsPerTx;
        proposalTtl = proposalTtl == null ? Duration.ofMinutes(30) : proposalTtl;
    }
}
