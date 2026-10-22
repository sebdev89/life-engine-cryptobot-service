package io.lifeengine.cryptobot.signer;

import java.time.Clock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * The pilot's daily cap on USDC spent by swaps ({@code signer.swaps.max-usdc-per-day}, base units): the lamports
 * ledger only sees fees when the input is USDC. Same {@link DailySpendLedger} — rolling 24 h, fsync'd file — in its
 * own file next to the lamports one ({@code <spend-ledger-path>.usdc}), mandatory on mainnet like the other.
 */
@Component
public class UsdcSpendLedger {

    private final DailySpendLedger ledger;

    @Autowired
    public UsdcSpendLedger(SignerProperties props, SwapProperties swaps) {
        this(props, swaps, Clock.systemUTC());
    }

    UsdcSpendLedger(SignerProperties props, SwapProperties swaps, Clock clock) {
        String path = props.spendLedgerPath().isEmpty() ? "" : props.spendLedgerPath() + ".usdc";
        this.ledger = new DailySpendLedger(swaps.maxUsdcPerDay(), path, props.allowMainnet(), "usdc", clock);
    }

    public DailySpendLedger ledger() {
        return ledger;
    }
}
