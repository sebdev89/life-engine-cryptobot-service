package io.lifeengine.cryptobot.domain.oracle;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The oracle as a pure function (KAN-439, paper §22): {@code consensus(asset, observations,
 * previous, now, limits) → OracleConsensus}. No I/O, no clock of its own, no randomness — the
 * same observations under the same limits give the same median, the same refusals and the same
 * {@code quotesHash} on every machine, so a decision can be re-derived from the quotes it names.
 *
 * <p>Order of the checks, all recorded (no short-circuit on the observation level):
 * <ol>
 *   <li>each observation is valid (positive price, a timestamp, not from the future, not older
 *       than {@code max_age}); one observation per source (the newest);
 *   <li>quorum: at least {@code min_sources} survivors, else {@code INSUFFICIENT_SOURCES};
 *   <li>the median of the survivors;
 *   <li>deviation: every survivor within {@code max_deviation_bps} of the median, else
 *       {@code DEVIATION_EXCEEDED};
 *   <li>circuit breaker: the median within {@code max_move_bps} of the last accepted consensus
 *       younger than {@code move_interval}, else {@code CIRCUIT_BREAKER}.
 * </ol>
 * Any refusal ⇒ not accepted ⇒ the asset has no price ⇒ the policy denies ({@code Unknown ⇒ Deny}).
 */
public final class PriceOracle {

    /** A source's clock may run slightly ahead of ours; beyond this an observation is "from the future" and rejected. */
    public static final Duration CLOCK_SKEW = Duration.ofSeconds(5);
    private static final BigDecimal BPS = BigDecimal.valueOf(OracleLimits.MAX_BPS);

    /** Deterministic order: by source, then newest first, then price — the order the quotes hash uses. */
    static final Comparator<PriceObservation> OBSERVATION_ORDER = Comparator
            .comparing(PriceObservation::source)
            .thenComparing(PriceObservation::observedAt, Comparator.nullsLast(Comparator.reverseOrder()))
            .thenComparing(PriceObservation::priceUsd, Comparator.nullsLast(Comparator.naturalOrder()));

    private PriceOracle() {}

    /**
     * @param asset the symbol being priced (observations of other assets are rejected, not ignored)
     * @param mint the mainnet mint the sources were asked about (informational; goes into the hash)
     * @param observations everything the sources returned for this asset
     * @param previous the last <em>accepted</em> consensus of this asset in this process, if any
     * @param now evaluation time (freshness and the breaker interval are measured against it)
     */
    public static OracleConsensus consensus(String asset, String mint, List<PriceObservation> observations, OracleConsensus previous,
            Instant now, OracleLimits limits) {
        if (limits == null) {
            throw new IllegalArgumentException("limits: missing (an oracle without limits accepts nothing)");
        }
        if (now == null) {
            throw new IllegalArgumentException("now: missing");
        }
        String wanted = asset == null ? "" : asset.trim().toUpperCase(java.util.Locale.ROOT);
        List<PriceObservation> all = observations == null ? List.of() : observations.stream().sorted(OBSERVATION_ORDER).toList();
        List<PriceObservation> used = new ArrayList<>();
        List<OracleConsensus.Rejected> rejected = new ArrayList<>();
        List<OracleRefusal> refusals = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        Instant oldestAllowed = now.minusSeconds(limits.maxAgeSeconds());
        Instant latestAllowed = now.plus(CLOCK_SKEW);
        Map<String, PriceObservation> bySource = new LinkedHashMap<>();

        for (PriceObservation o : all) {
            String reason = null;
            if (!wanted.equals(o.asset())) {
                reason = "OTHER_ASSET";
            } else if (o.priceUsd() == null || o.priceUsd().signum() <= 0) {
                reason = "INVALID_PRICE";
            } else if (o.observedAt() == null) {
                reason = "NO_TIMESTAMP";
            } else if (o.observedAt().isAfter(latestAllowed)) {
                reason = "FUTURE";
            } else if (o.observedAt().isBefore(oldestAllowed)) {
                reason = "STALE";
            } else if (bySource.containsKey(o.source())) {
                reason = "DUPLICATE_SOURCE";
            }
            if (reason == null) {
                bySource.put(o.source(), o);
                used.add(o);
            } else {
                rejected.add(new OracleConsensus.Rejected(o, reason));
            }
        }

        String quotesHash = OracleConsensus.quotesHash(wanted, mint, all);
        if (all.isEmpty()) {
            refusals.add(OracleRefusal.NO_OBSERVATIONS);
            problems.add("no source answered for " + wanted);
            return new OracleConsensus(wanted, mint, null, null, used, rejected, refusals, problems, quotesHash);
        }
        if (used.size() < limits.minSources()) {
            refusals.add(OracleRefusal.INSUFFICIENT_SOURCES);
            problems.add(used.size() + " usable source(s) for " + wanted + ", quorum is " + limits.minSources()
                    + (rejected.isEmpty() ? "" : " (rejected: " + rejected.stream().map(r -> r.observation().source() + "=" + r.reason()).toList() + ")"));
            return new OracleConsensus(wanted, mint, null, null, used, rejected, refusals, problems, quotesHash);
        }

        BigDecimal median = median(used.stream().map(PriceObservation::priceUsd).sorted().toList());
        Instant asOf = used.stream().map(PriceObservation::observedAt).min(Comparator.naturalOrder()).orElse(null);

        for (PriceObservation o : used) {
            int deviation = deviationBps(o.priceUsd(), median);
            if (deviation > limits.maxDeviationBps()) {
                if (!refusals.contains(OracleRefusal.DEVIATION_EXCEEDED)) {
                    refusals.add(OracleRefusal.DEVIATION_EXCEEDED);
                }
                problems.add(o.source() + " says " + plain(o.priceUsd()) + ", median is " + plain(median) + ": " + deviation
                        + " bps apart, limit " + limits.maxDeviationBps());
            }
        }

        if (previous != null && previous.accepted() && wanted.equals(previous.asset()) && limits.moveIntervalSeconds() > 0
                && !previous.asOf().isAfter(now) && Duration.between(previous.asOf(), now).getSeconds() <= limits.moveIntervalSeconds()) {
            int move = deviationBps(median, previous.priceUsd());
            if (move > limits.maxMoveBps()) {
                refusals.add(OracleRefusal.CIRCUIT_BREAKER);
                problems.add(wanted + " moved " + move + " bps (" + plain(previous.priceUsd()) + " → " + plain(median) + ") in "
                        + Duration.between(previous.asOf(), now).getSeconds() + "s; limit " + limits.maxMoveBps() + " bps per "
                        + limits.moveIntervalSeconds() + "s");
            }
        }
        return new OracleConsensus(wanted, mint, median, asOf, used, rejected, refusals, problems, quotesHash);
    }

    /** Median of an ascending list; an even count averages the two middle values (one extra decimal, half-up). */
    static BigDecimal median(List<BigDecimal> ascending) {
        int n = ascending.size();
        if (n == 0) {
            return null;
        }
        if (n % 2 == 1) {
            return ascending.get(n / 2);
        }
        BigDecimal a = ascending.get(n / 2 - 1);
        BigDecimal b = ascending.get(n / 2);
        return a.add(b).divide(BigDecimal.valueOf(2), Math.max(a.scale(), b.scale()) + 1, RoundingMode.HALF_UP);
    }

    /**
     * {@code |value − reference| / reference} in basis points, rounded <em>up</em> (a deviation is
     * never rounded into the limit). A non-positive reference is infinitely far: {@code MAX_BPS + 1}.
     */
    public static int deviationBps(BigDecimal value, BigDecimal reference) {
        if (value == null || reference == null || reference.signum() <= 0) {
            return OracleLimits.MAX_BPS + 1;
        }
        BigDecimal bps = value.subtract(reference).abs().multiply(BPS).divide(reference, 0, RoundingMode.CEILING);
        return bps.compareTo(BigDecimal.valueOf(Integer.MAX_VALUE)) > 0 ? Integer.MAX_VALUE : bps.intValueExact();
    }

    static String plain(BigDecimal v) {
        return v == null ? "?" : v.stripTrailingZeros().toPlainString();
    }
}
