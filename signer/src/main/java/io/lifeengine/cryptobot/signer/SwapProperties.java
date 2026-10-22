package io.lifeengine.cryptobot.signer;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/**
 * The pilot's swap signing ({@code signer.swaps.*}, {@code POST /api/signer/sign-swap}). Off by default: a signer
 * signs swaps only where gitops turns {@code SIGNER_SWAPS_ENABLED} on, and on mainnet only together with
 * {@code SIGNER_ALLOW_MAINNET=true} ({@link SwapGuard} refuses to start otherwise). Every limit is the signer's own,
 * independent of the service and the validator; see {@link SwapSigningPolicy}.
 *
 * @param maxLamportsIn largest SOL input of one swap (default 0.1 SOL)
 * @param maxUsdcIn largest USDC input of one swap, base units (default 10 USDC)
 * @param maxPriorityFeeLamports largest priority fee of one swap (default 200 000 lamports)
 * @param maxSlippageBps largest slippage the route may carry (default 100)
 * @param minUsdcPerSol gross floor: USDC base units per SOL in; unset (0) refuses SOL→USDC
 * @param minLamportsPerUsdc gross floor: lamports per USDC in; unset (0) refuses USDC→SOL
 * @param maxUsdcPerDay USDC (base units) swaps may spend in any rolling 24 h (default 50 USDC); SOL spend is the
 *     lamports cap ({@code signer.max-lamports-per-day})
 */
@ConfigurationProperties(prefix = "signer.swaps")
public record SwapProperties(
        Boolean enabled,
        Long maxLamportsIn,
        Long maxUsdcIn,
        Long maxPriorityFeeLamports,
        Integer maxSlippageBps,
        Long minUsdcPerSol,
        Long minLamportsPerUsdc,
        Long maxUsdcPerDay) {

    @ConstructorBinding
    public SwapProperties {
        enabled = enabled != null && enabled;
        maxLamportsIn = positive(maxLamportsIn, 100_000_000L);
        maxUsdcIn = positive(maxUsdcIn, 10_000_000L);
        maxPriorityFeeLamports = positive(maxPriorityFeeLamports, 200_000L);
        maxSlippageBps = maxSlippageBps == null || maxSlippageBps < 0 ? 100 : Math.min(maxSlippageBps, 10_000);
        minUsdcPerSol = minUsdcPerSol == null || minUsdcPerSol < 0 ? 0L : minUsdcPerSol;
        minLamportsPerUsdc = minLamportsPerUsdc == null || minLamportsPerUsdc < 0 ? 0L : minLamportsPerUsdc;
        maxUsdcPerDay = positive(maxUsdcPerDay, 50_000_000L);
    }

    public SwapSigningPolicy.Limits limits() {
        return new SwapSigningPolicy.Limits(maxLamportsIn, maxUsdcIn, maxPriorityFeeLamports, maxSlippageBps, minUsdcPerSol, minLamportsPerUsdc);
    }

    private static long positive(Long v, long dflt) {
        return v == null || v <= 0 ? dflt : v;
    }
}
