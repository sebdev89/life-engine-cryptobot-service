package io.lifeengine.cryptobot.core.ports;

import io.lifeengine.cryptobot.core.Network;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import reactor.core.publisher.Mono;

/**
 * KAN-596 — what a wallet holds, read-only. No mainnet gate: reading a mainnet address's balance
 * is exactly the "read-only lecturas de mainnet son libres" case the vertical's rules call out.
 */
public interface AssetPort {

    Mono<Long> balanceLamports(Network network, String address);

    /** SPL + Token-2022 accounts owned by {@code address}, with parsed balances. Zero balances included. */
    Mono<List<TokenAccountBalance>> tokenAccounts(Network network, String address);

    record TokenAccountBalance(
            String mint, String tokenAccount, String programId, BigInteger amountRaw, int decimals, BigDecimal uiAmount) {}
}
