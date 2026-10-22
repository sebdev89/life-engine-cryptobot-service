package io.lifeengine.cryptobot.application.swap;

import io.lifeengine.cryptobot.adapters.jupiter.JupiterSwapClient;
import io.lifeengine.cryptobot.adapters.jupiter.SwapQuote;
import io.lifeengine.cryptobot.adapters.jupiter.UnsignedSwap;
import io.lifeengine.cryptobot.adapters.jupiter.UnsignedSwapCheck;
import io.lifeengine.cryptobot.solana.rpc.SolanaCluster;
import io.lifeengine.cryptobot.solana.rpc.SolanaRpcClient;
import io.lifeengine.cryptobot.solana.rpc.SolanaRpcClient.SimulationResult;
import reactor.core.publisher.Mono;

/**
 * KAN-1249: quote a swap on Jupiter, have it build the transaction for a public key, check the bytes and simulate
 * them on mainnet — and stop there. Jupiter has no devnet, so this is the only way to prove a real swap would go
 * through without moving funds.
 *
 * <p>Nothing here signs or broadcasts. The only RPC method it calls is {@code simulateTransaction}, with
 * {@code sigVerify=false} (the signature slot stays empty) and a replaced blockhash; it holds no signer client and
 * no keypair, and ModuleBoundariesTest fails the build if that changes. The mainnet gate
 * ({@code CRYPTOBOT_ALLOW_MAINNET}) is not involved because nothing is sent.
 *
 * <p>Not wired as a bean yet: calling it against mainnet waits for Sebas's OK and a public address (KAN-1249).
 */
public class SwapPreviewService {

    /** What a preview found. {@code simulation.ok()} is the answer; the rest is the evidence. */
    public record SwapPreview(SwapQuote quote, UnsignedSwap swap, UnsignedSwapCheck.Checked checked, SimulationResult simulation) {}

    private final JupiterSwapClient jupiter;
    private final SolanaRpcClient rpc;

    public SwapPreviewService(JupiterSwapClient jupiter, SolanaRpcClient rpc) {
        this.jupiter = jupiter;
        this.rpc = rpc;
    }

    public Mono<SwapPreview> preview(String inputMint, String outputMint, long amount, int slippageBps, String ownerPublicKey) {
        return jupiter.quote(inputMint, outputMint, amount, slippageBps)
                .flatMap(quote -> jupiter.buildSwap(quote, ownerPublicKey)
                        .flatMap(swap -> {
                            // a transaction that fails the check is not even simulated
                            UnsignedSwapCheck.Checked checked = UnsignedSwapCheck.check(swap.transactionBase64(), ownerPublicKey);
                            return rpc.simulateTransaction(SolanaCluster.MAINNET_BETA, swap.transactionBase64(), false)
                                    .map(sim -> new SwapPreview(quote, swap, checked, sim));
                        }));
    }
}
