package io.lifeengine.cryptobot.core.ports;

import io.lifeengine.cryptobot.core.Network;
import reactor.core.publisher.Mono;

/**
 * The write side of the chain: a fresh blockhash to build against, and broadcasting a
 * signed transaction. {@code submit} is where mainnet is refused a second time, independently of
 * whichever adapter answers this port (audit §6, defense in depth): gate #2 stays inside the
 * concrete adapter (e.g. {@code SolanaRpcClient.sendTransaction}, {@code
 * cryptobot.execution.allow-mainnet}); this interface's contract adds the core-level rule —
 * {@link io.lifeengine.cryptobot.core.execution.NetworkPolicy} — so a future non-Solana
 * implementation cannot forget it.
 */
public interface ChainExecutionPort {

    Mono<LatestBlockhash> latestBlockhash(Network network);

    /**
     * @throws io.lifeengine.cryptobot.core.execution.MainnetRefusedException when {@code network}
     *     is {@link Network#MAINNET_BETA} and the core's {@link
     *     io.lifeengine.cryptobot.core.execution.NetworkPolicy} does not allow it — before anything
     *     reaches the adapter's own gate.
     */
    Mono<Submission> submit(Network network, String signedTransactionBase64);

    record LatestBlockhash(String blockhash, long lastValidBlockHeight) {}

    /** @param signature the transaction id — known before anyone confirms it landed. */
    record Submission(String signature) {}
}
