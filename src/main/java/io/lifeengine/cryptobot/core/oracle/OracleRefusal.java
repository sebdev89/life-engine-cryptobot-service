package io.lifeengine.cryptobot.core.oracle;

/** Why a consensus was not reached. Any refusal ⇒ the asset has no price ⇒ DENY (§17, §22). */
public enum OracleRefusal {
    /** No source answered at all. */
    NO_OBSERVATIONS,
    /** Fewer fresh, valid, distinct sources than {@code min_sources}. */
    INSUFFICIENT_SOURCES,
    /** A source used is further than {@code max_deviation_bps} from the median: the sources disagree. */
    DEVIATION_EXCEEDED,
    /** The median moved more than {@code max_move_bps} against the last accepted consensus within the interval. */
    CIRCUIT_BREAKER
}
