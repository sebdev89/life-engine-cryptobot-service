package io.lifeengine.cryptobot.core.ports;

import reactor.core.publisher.Mono;

/**
 * KAN-596 — anchors a Merkle root of receipt hashes on-chain: builds, signs and broadcasts the
 * memo transaction that carries {@code root} and {@code count} (Endgame §11, KAN-394). Devnet
 * only, by construction of the one implementation ({@code AnchorService}, {@code AnchorProperties}
 * refuses any other cluster at startup) — no {@code Network} parameter, unlike the chain ports:
 * anchoring is not something a caller chooses a network for, it is a fixed operational decision
 * ("mainnet anchoring is a human decision with real money", {@code AnchorService}'s own docs).
 */
public interface AnchorPort {

    Mono<ChainExecutionPort.Submission> anchor(String root, int count);
}
