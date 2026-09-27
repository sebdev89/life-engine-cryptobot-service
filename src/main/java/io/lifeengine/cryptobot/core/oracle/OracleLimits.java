package io.lifeengine.cryptobot.core.oracle;

import io.lifeengine.cryptobot.core.intent.JsonCanonicalizer;
import io.lifeengine.cryptobot.core.receipts.Digests;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The data-integrity assumptions of the execution envelope (paper §9, §22): what a price has to
 * satisfy before the deterministic policy is allowed to see it. Integers only, like
 * {@code PolicyRules}; {@link #hash()} is committed in every decision so a verifier knows under
 * which assumptions the state was accepted.
 *
 * <ul>
 *   <li>{@code minSources} — quorum: independent sources that must agree. Never below 2: one
 *       source is an opinion, not a consensus.
 *   <li>{@code maxAgeSeconds} — freshness: an observation older than this is not a fact.
 *   <li>{@code maxDeviationBps} — deviation bound: every source used must be within this of the
 *       median, or the sources disagree and nothing is accepted.
 *   <li>{@code maxMoveBps} / {@code moveIntervalSeconds} — circuit breaker: a median that moved
 *       more than this against the last accepted consensus younger than the interval trips it.
 *       The same bound is applied between the price a plan was built on and the fresh median:
 *       a plan priced at $18 while the world says $180 is refused, not executed.
 * </ul>
 */
public record OracleLimits(int minSources, long maxAgeSeconds, int maxDeviationBps, int maxMoveBps, long moveIntervalSeconds) {

    public static final String SCHEMA_VERSION = "oracle-limits/1";
    public static final int MAX_BPS = 10_000;

    public OracleLimits {
        if (minSources < 2) {
            throw new IllegalArgumentException("min_sources: must be at least 2, got " + minSources);
        }
        if (maxAgeSeconds <= 0 || maxAgeSeconds > JsonCanonicalizer.MAX_SAFE_INTEGER) {
            throw new IllegalArgumentException("max_age_seconds: must be positive, got " + maxAgeSeconds);
        }
        bps("max_deviation_bps", maxDeviationBps);
        bps("max_move_bps", maxMoveBps);
        if (moveIntervalSeconds < 0 || moveIntervalSeconds > JsonCanonicalizer.MAX_SAFE_INTEGER) {
            throw new IllegalArgumentException("move_interval_seconds: must be non-negative, got " + moveIntervalSeconds);
        }
    }

    public Map<String, Object> canonicalMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("schema_version", SCHEMA_VERSION);
        m.put("min_sources", minSources);
        m.put("max_age_seconds", maxAgeSeconds);
        m.put("max_deviation_bps", maxDeviationBps);
        m.put("max_move_bps", maxMoveBps);
        m.put("move_interval_seconds", moveIntervalSeconds);
        return m;
    }

    /** {@code sha256:…} of the RFC 8785 form: the integrity assumptions, as committed. */
    public String hash() {
        return Digests.sha256(JsonCanonicalizer.canonicalBytes(canonicalMap()));
    }

    private static void bps(String field, int v) {
        if (v < 0 || v > MAX_BPS) {
            throw new IllegalArgumentException(field + ": must be within 0.." + MAX_BPS + " bps, got " + v);
        }
    }
}
