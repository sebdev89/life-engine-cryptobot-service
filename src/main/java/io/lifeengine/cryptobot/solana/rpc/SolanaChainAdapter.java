package io.lifeengine.cryptobot.solana.rpc;

import io.lifeengine.cryptobot.core.Network;
import io.lifeengine.cryptobot.core.execution.MainnetRefusedException;
import io.lifeengine.cryptobot.core.execution.NetworkPolicy;
import io.lifeengine.cryptobot.core.ports.AssetPort;
import io.lifeengine.cryptobot.core.ports.ChainExecutionPort;
import io.lifeengine.cryptobot.core.ports.ChainObservationPort;
import io.lifeengine.cryptobot.core.ports.ChainSimulationPort;
import io.lifeengine.cryptobot.core.receipts.Digests;
import java.util.List;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * KAN-596 (TAE phase 1, gap G9) — the Solana implementation of the four chain ports, wrapping
 * {@link SolanaRpcClient}. No multi-chain today: this is the one adapter; a second chain would add
 * a sibling, never a branch inside this class.
 *
 * <p>Whichever {@link SolanaRpcClient} bean Spring wires in is what this adapter calls — when
 * {@code cryptobot.chaos.enabled=true} that bean is {@code ChaosSolanaRpcClient} ({@code @Primary}),
 * so the demo's fault injection on {@code sendTransaction}/{@code getSignatureStatuses}/{@code
 * getBlockHeight} keeps working through this port exactly as it did through the concrete client —
 * "chaos modes ... siguen funcionando vía decorator" without this adapter knowing chaos exists.
 *
 * <p>{@code submit} carries its own mainnet gate ({@link NetworkPolicy}, the core's copy of KAN-493)
 * <em>before</em> the adapter's own ({@link SolanaRpcClient#sendTransaction}, gate #2) — two
 * independent refusals, same as {@code ExecutionService.requireClusterAllowed} and the signer's own
 * check are independent of each other (audit §6, defense in depth).
 */
@Component
public class SolanaChainAdapter implements ChainSimulationPort, ChainExecutionPort, ChainObservationPort, AssetPort {

    private final SolanaRpcClient rpc;
    private final NetworkPolicy networkPolicy;

    public SolanaChainAdapter(SolanaRpcClient rpc, NetworkPolicy networkPolicy) {
        this.rpc = rpc;
        this.networkPolicy = networkPolicy == null ? NetworkPolicy.failClosed() : networkPolicy;
    }

    @Override
    public Mono<SimulationResult> simulate(Network network, String unsignedTransactionBase64) {
        return rpc.simulateTransaction(SolanaCluster.from(network), unsignedTransactionBase64, false)
                .map(r -> new SimulationResult(r.ok(), r.error(), r.unitsConsumed(), logsHash(r.logs())));
    }

    @Override
    public Mono<LatestBlockhash> latestBlockhash(Network network) {
        return rpc.getLatestBlockhash(SolanaCluster.from(network))
                .map(bh -> new LatestBlockhash(bh.blockhash(), bh.lastValidBlockHeight()));
    }

    @Override
    public Mono<Submission> submit(Network network, String signedTransactionBase64) {
        if (!networkPolicy.permits(network)) {
            return Mono.error(new MainnetRefusedException("submit", network));
        }
        return rpc.sendTransaction(SolanaCluster.from(network), signedTransactionBase64).map(Submission::new);
    }

    @Override
    public Mono<SignatureStatus> status(Network network, String signature) {
        return rpc.getSignatureStatus(SolanaCluster.from(network), signature)
                .map(s -> new SignatureStatus(s.signature(), s.confirmationStatus(), s.failed(), s.error(), s.slot()));
    }

    @Override
    public Mono<Long> blockHeight(Network network) {
        return rpc.getBlockHeight(SolanaCluster.from(network));
    }

    @Override
    public Mono<TransactionInfo> transaction(Network network, String signature) {
        return rpc.getTransaction(SolanaCluster.from(network), signature)
                .map(t -> new TransactionInfo(t.signature(), t.slot(), t.blockTime(), t.failed(), t.error(), t.memos()));
    }

    @Override
    public Mono<Long> balanceLamports(Network network, String address) {
        return rpc.getBalanceLamports(SolanaCluster.from(network), address);
    }

    @Override
    public Mono<List<TokenAccountBalance>> tokenAccounts(Network network, String address) {
        return rpc.getTokenAccountsByOwner(SolanaCluster.from(network), address)
                .map(list -> list.stream()
                        .map(a -> new TokenAccountBalance(a.mint(), a.tokenAccount(), a.programId(), a.amountRaw(), a.decimals(), a.uiAmount()))
                        .toList());
    }

    /** {@code null} for an empty log (nothing to redact); {@code sha256:…} of the lines joined with {@code \n} otherwise. */
    static String logsHash(List<String> logs) {
        return logs == null || logs.isEmpty() ? null : Digests.sha256(String.join("\n", logs));
    }
}
