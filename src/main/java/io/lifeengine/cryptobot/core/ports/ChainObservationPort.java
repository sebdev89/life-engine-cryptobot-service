package io.lifeengine.cryptobot.core.ports;

import io.lifeengine.cryptobot.core.Network;
import java.time.Instant;
import java.util.List;
import reactor.core.publisher.Mono;

/**
 * Read-only, after the fact: has a signature been seen, is the node past a given block
 * height, and what a finalized transaction actually carried (memos: how the anchor's root is read
 * back). Read-only means these calls are free of the mainnet gate — nothing here signs or
 * broadcasts.
 */
public interface ChainObservationPort {

    Mono<SignatureStatus> status(Network network, String signature);

    /** Current block height (not slot) — the fact that decides a never-seen signature can never land. */
    Mono<Long> blockHeight(Network network);

    Mono<TransactionInfo> transaction(Network network, String signature);

    /** {@code slot} is the slot the transaction was processed in, when the node reports one. */
    record SignatureStatus(String signature, String confirmationStatus, boolean failed, String error, Long slot) {}

    record TransactionInfo(String signature, long slot, Instant blockTime, boolean failed, String error, List<String> memos) {
        public TransactionInfo {
            memos = memos == null ? List.of() : List.copyOf(memos);
        }
    }
}
