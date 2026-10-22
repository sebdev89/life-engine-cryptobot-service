package io.lifeengine.cryptobot.signer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Refuses to start a signer that would sign swaps on mainnet without the mainnet gate: {@code SIGNER_SWAPS_ENABLED}
 * and a mainnet {@code SIGNER_CLUSTER} need {@code SIGNER_ALLOW_MAINNET=true} too. Both are gitops settings, so
 * opening mainnet swaps is one reviewed change, never a side effect of turning the feature on.
 */
@Component
public class SwapGuard {

    private static final Logger log = LoggerFactory.getLogger(SwapGuard.class);

    public SwapGuard(SignerProperties props, SwapProperties swaps) {
        if (swaps.enabled() && SignerProperties.isMainnet(props.cluster()) && !props.allowMainnet()) {
            throw new IllegalStateException("signer.swaps.enabled=true on mainnet requires signer.allow-mainnet=true (SIGNER_ALLOW_MAINNET)");
        }
        if (swaps.enabled()) {
            log.warn("signer_swaps_enabled cluster={} max_lamports_in={} max_usdc_in={} max_slippage_bps={} floor_sol_usdc={} floor_usdc_sol={}",
                    props.cluster(), swaps.maxLamportsIn(), swaps.maxUsdcIn(), swaps.maxSlippageBps(), swaps.minUsdcPerSol(), swaps.minLamportsPerUsdc());
        } else {
            log.info("signer_swaps_disabled");
        }
    }
}
