package io.lifeengine.cryptobot.core.ports;

import io.lifeengine.cryptobot.core.Network;
import reactor.core.publisher.Mono;

/**
 * KAN-596 (TAE phase 1, audit §20/G9, gap chain ports) — simulating an unsigned transaction
 * without knowing which chain adapter answers. The one implementation today wraps {@code
 * SolanaRpcClient.simulateTransaction}; {@code logsHash} instead of the raw log lines because the
 * core has no business inspecting a chain-specific program log — only whether it is the same log
 * a human already saw (recomputable, never compared line by line).
 */
public interface ChainSimulationPort {

    Mono<SimulationResult> simulate(Network network, String unsignedTransactionBase64);

    /**
     * @param ok whether the node accepted the transaction, unsigned, {@code sigVerify=false}
     * @param error the node's error, {@code null} when {@code ok}
     * @param unitsConsumed compute units the simulation reports, {@code null} when the node did not
     * @param logsHash {@code sha256:…} of the program logs joined with {@code \n}; {@code null} for
     *     an empty log
     */
    record SimulationResult(boolean ok, String error, Long unitsConsumed, String logsHash) {}
}
