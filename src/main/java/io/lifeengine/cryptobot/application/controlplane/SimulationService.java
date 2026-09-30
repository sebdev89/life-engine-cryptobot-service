package io.lifeengine.cryptobot.application.controlplane;

import io.lifeengine.cryptobot.solana.rpc.Base58;
import io.lifeengine.cryptobot.solana.rpc.SolanaCluster;
import io.lifeengine.cryptobot.solana.rpc.SolanaRpcClient;
import io.lifeengine.cryptobot.solana.tx.LegacyTransaction;
import io.lifeengine.cryptobot.solana.tx.SystemProgram;
import io.lifeengine.cryptobot.trading.portfolio.PortfolioSnapshot;
import io.lifeengine.cryptobot.trading.strategy.RebalanceLeg;
import io.lifeengine.cryptobot.trading.strategy.RebalancePlan;
import io.lifeengine.cryptobot.core.execution.PreparedTransaction;
import io.lifeengine.cryptobot.core.execution.SimulationOutcome;
import io.lifeengine.cryptobot.core.wallet.Wallet;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * Economic simulation (what the trade is worth) + on-chain simulation (would this exact
 * transaction succeed). The transaction is built <b>unsigned</b>; simulation runs with
 * {@code sigVerify=false}, so a read-only mainnet wallet can be simulated just like the devnet
 * demo wallet.
 */
@Service
public class SimulationService {

    private static final Logger log = LoggerFactory.getLogger(SimulationService.class);
    /** 5 000 lamports per signature is the base fee on every cluster today. */
    static final BigDecimal BASE_FEE_SOL = new BigDecimal("0.000005");

    public record Simulated(SimulationOutcome outcome, PreparedTransaction transaction) {}

    private final SolanaRpcClient rpc;
    private final PolicyProperties policy;

    public SimulationService(SolanaRpcClient rpc, PolicyProperties policy) {
        this.rpc = rpc;
        this.policy = policy;
    }

    public Mono<Simulated> simulate(Wallet wallet, PortfolioSnapshot snapshot, RebalancePlan plan) {
        Optional<RebalanceLeg> sell = plan.legs().stream().filter(l -> l.action() == RebalanceLeg.Action.SELL).findFirst();
        SimulationOutcome.Economic economic = sell.map(l -> economic(l, snapshot)).orElse(null);

        Optional<RebalanceLeg> solLeg = sell.filter(l -> "SOL".equalsIgnoreCase(l.symbol()));
        if (solLeg.isEmpty()) {
            SimulationOutcome.Onchain onchain = new SimulationOutcome.Onchain(false, "NOT_EXECUTABLE_ASSET: only a SOL sell leg is executable in this version", null, List.of(), wallet.cluster().id());
            return Mono.just(new Simulated(new SimulationOutcome(economic, onchain), null));
        }
        long lamports = solLeg.get().amount().multiply(BigDecimal.valueOf(SolanaRpcClient.LAMPORTS_PER_SOL)).setScale(0, RoundingMode.DOWN).longValueExact();
        if (policy.rebalanceVault().isEmpty() || !Base58.isPublicKey(policy.rebalanceVault())) {
            SimulationOutcome.Onchain onchain = new SimulationOutcome.Onchain(false, "NO_VAULT: rebalance vault not configured", null, List.of(), wallet.cluster().id());
            return Mono.just(new Simulated(new SimulationOutcome(economic, onchain), null));
        }
        return prepareTransfer(wallet, lamports)
                .flatMap(tx -> rpc.simulateTransaction(SolanaCluster.from(wallet.cluster()), tx.unsignedTransactionBase64(), false)
                        .map(r -> new Simulated(new SimulationOutcome(economic,
                                new SimulationOutcome.Onchain(r.ok(), r.error(), r.unitsConsumed(), r.logs(), wallet.cluster().id())), tx))
                        .onErrorResume(ex -> {
                            log.warn("onchain_simulation_failed walletId={} error={}", wallet.id(), ex.toString());
                            return Mono.just(new Simulated(new SimulationOutcome(economic,
                                    new SimulationOutcome.Onchain(false, "RPC: " + ex.getMessage(), null, List.of(), wallet.cluster().id())), tx));
                        }));
    }

    /** Builds a fresh unsigned {@code SystemProgram.transfer(wallet → vault, lamports)} with a current blockhash. */
    public Mono<PreparedTransaction> prepareTransfer(Wallet wallet, long lamports) {
        return prepareTransfer(SolanaCluster.from(wallet.cluster()), wallet.address(), policy.rebalanceVault(), lamports, "rebalance vault");
    }

    /**
     * the same unsigned {@code SystemProgram.transfer(from → to, lamports)} on a fresh blockhash, to any
     * destination — the caller (a Proof of Value payout) names it; the signer's allowlist is still the last word.
     * {@code label} only goes into the human-readable summary.
     */
    public Mono<PreparedTransaction> prepareTransfer(SolanaCluster cluster, String from, String to, long lamports, String label) {
        return rpc.getLatestBlockhash(cluster)
                .map(bh -> {
                    LegacyTransaction tx = new LegacyTransaction(from, bh.blockhash(), List.of(SystemProgram.transfer(from, to, lamports)));
                    return new PreparedTransaction(cluster.id(), from, to, lamports, bh.blockhash(), bh.lastValidBlockHeight(), tx.unsignedBase64(),
                            tx.messageBase64(), "SystemProgram.transfer " + lamports + " lamports ("
                                    + BigDecimal.valueOf(lamports).divide(BigDecimal.valueOf(SolanaRpcClient.LAMPORTS_PER_SOL), 9, RoundingMode.DOWN)
                                            .stripTrailingZeros().toPlainString()
                                    + " SOL) from " + from + " to " + label + " " + to);
                });
    }

    private static SimulationOutcome.Economic economic(RebalanceLeg leg, PortfolioSnapshot snapshot) {
        BigDecimal price = snapshot.position(leg.symbol()).map(p -> p.priceUsd()).orElse(BigDecimal.ZERO);
        BigDecimal counterPrice = snapshot.position(leg.counterAsset()).map(p -> p.priceUsd()).orElse(BigDecimal.ONE);
        BigDecimal gross = leg.amount().multiply(price);
        BigDecimal expectedOut = counterPrice.signum() == 0 ? BigDecimal.ZERO : gross.divide(counterPrice, 6, RoundingMode.DOWN);
        return new SimulationOutcome.Economic(leg.symbol(), leg.amount(), leg.counterAsset(), expectedOut, price, BASE_FEE_SOL, BigDecimal.ZERO,
                "spot:" + snapshot.priceSource());
    }
}
