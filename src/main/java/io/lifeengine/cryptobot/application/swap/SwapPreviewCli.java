package io.lifeengine.cryptobot.application.swap;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.lifeengine.cryptobot.adapters.jupiter.JupiterSwapClient;
import io.lifeengine.cryptobot.adapters.marketdata.MarketDataProperties;
import io.lifeengine.cryptobot.solana.rpc.SolanaRpcClient;
import io.lifeengine.cryptobot.solana.rpc.SolanaRpcProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * KAN-1249, on demand only (never a cron or a loop): quote a SOL→USDC swap on Jupiter, have it built for a public
 * address, check the bytes and simulate them on mainnet with {@link SwapPreviewService} — the same path, so the same
 * guarantee: no signer, no keypair, only {@code simulateTransaction} (ModuleBoundariesTest). Nothing is signed or
 * sent and no funds move: a simulation is a read.
 *
 * <p>Usage: {@code SwapPreviewCli [lamports] [slippageBps] [owner] [txOutFile]}. The simulation needs a fee payer
 * that holds SOL; the default {@link #DEFAULT_OWNER} is a public institutional address, never a person's wallet.
 */
public final class SwapPreviewCli {

    /**
     * Binance's Solana hot wallet, labelled "Binance 2" by Solscan (https://solscan.io/account/5tzFkiKscXHK5ZXCGbXZxdw7gTjjD1mBwuoFbhUvuAi9).
     * Checked on 2026-10-22: a System-owned account holding ~1.43 M SOL. Only its public address is used, as the fee
     * payer of a simulation; Binance's funds are not and cannot be touched.
     */
    public static final String DEFAULT_OWNER = "5tzFkiKscXHK5ZXCGbXZxdw7gTjjD1mBwuoFbhUvuAi9";
    public static final String SOL = "So11111111111111111111111111111111111111112";
    public static final String USDC = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v";
    static final String MAINNET_RPC = "https://api.mainnet-beta.solana.com";
    static final String JUPITER = "https://lite-api.jup.ag";

    private SwapPreviewCli() {}

    public static void main(String[] args) throws Exception {
        long lamports = args.length > 0 ? Long.parseLong(args[0]) : 10_000_000L;
        int slippage = args.length > 1 ? Integer.parseInt(args[1]) : 50;
        String owner = args.length > 2 ? args[2] : DEFAULT_OWNER;
        ObjectMapper json = new ObjectMapper();
        MarketDataProperties md = new MarketDataProperties(JUPITER, Duration.ofSeconds(15), Map.of(), Map.of(), true);
        // devnet is never used on this path; both URLs point at mainnet so a mistake cannot reach a different chain
        SolanaRpcClient rpc = new SolanaRpcClient(WebClient.builder(), new SolanaRpcProperties(MAINNET_RPC, MAINNET_RPC, Duration.ofSeconds(20)), json);
        SwapPreviewService.SwapPreview p = new SwapPreviewService(new JupiterSwapClient(WebClient.builder(), md, json), rpc)
                .preview(SOL, USDC, lamports, slippage, owner)
                .block(Duration.ofSeconds(60));
        System.out.println("owner=" + owner);
        System.out.println("quote in=" + p.quote().inAmount() + " out=" + p.quote().outAmount() + " min_out=" + p.quote().minOutAmount()
                + " slippage_bps=" + p.quote().slippageBps() + " impact_pct=" + p.quote().priceImpactPct() + " route=" + p.quote().routeLabels());
        System.out.println("check fee_payer=" + p.checked().feePayer() + " programs=" + p.checked().programs());
        System.out.println("jupiter_presimulation_error=" + p.swap().jupiterSimulationError() + " priority_fee_lamports=" + p.swap().prioritizationFeeLamports());
        System.out.println("simulation ok=" + p.simulation().ok() + " error=" + p.simulation().error() + " units=" + p.simulation().unitsConsumed());
        p.simulation().logs().forEach(l -> System.out.println("  log " + l));
        if (args.length > 3) {
            Files.writeString(Path.of(args[3]), p.swap().transactionBase64());
        }
    }
}
