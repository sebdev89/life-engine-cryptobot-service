package io.lifeengine.cryptobot.domain.oracle;

import io.lifeengine.cryptobot.domain.intent.JsonCanonicalizer;
import io.lifeengine.cryptobot.domain.receipt.Digests;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What the oracle concluded about one asset from what it saw (KAN-439). {@code priceUsd} is the
 * median of the sources {@code used}; it is only a price when {@link #accepted()} — a refused
 * consensus keeps the median (if there was one) for the audit trail, and nothing downstream may
 * treat it as a fact. {@code asOf} is the oldest observation the median depends on: the age the
 * policy's {@code ORACLE_FRESH} predicate sees.
 *
 * <p>{@code quotesHash} is {@code sha256} of every observation the oracle saw for this asset —
 * used and rejected alike — so the receipt's state reference names exactly the quotes behind
 * the decision, and a verifier with the same quotes recomputes the same median.
 */
public record OracleConsensus(
        String asset,
        String mint,
        BigDecimal priceUsd,
        Instant asOf,
        List<PriceObservation> used,
        List<Rejected> rejected,
        List<OracleRefusal> refusals,
        List<String> problems,
        String quotesHash) {

    public static final String SCHEMA_VERSION = "oracle-quotes/1";

    /** One observation the oracle would not use, and why. */
    public record Rejected(PriceObservation observation, String reason) {}

    public OracleConsensus {
        used = used == null ? List.of() : List.copyOf(used);
        rejected = rejected == null ? List.of() : List.copyOf(rejected);
        refusals = refusals == null ? List.of() : List.copyOf(refusals);
        problems = problems == null ? List.of() : List.copyOf(problems);
    }

    /** A price the policy may rely on: quorum reached, sources agree, no breaker tripped. */
    public boolean accepted() {
        return refusals.isEmpty() && priceUsd != null && priceUsd.signum() > 0 && asOf != null;
    }

    public List<String> sources() {
        return used.stream().map(PriceObservation::source).toList();
    }

    /** {@code oracle-quotes/1}: the asset and every observation, sorted by source — what was seen. */
    public static String quotesHash(String asset, String mint, List<PriceObservation> all) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("schema_version", SCHEMA_VERSION);
        m.put("asset", asset);
        if (mint != null) {
            m.put("mint", mint);
        }
        List<Map<String, Object>> obs = new ArrayList<>();
        all.stream()
                .sorted(PriceOracle.OBSERVATION_ORDER)
                .forEach(o -> obs.add(o.canonicalMap()));
        m.put("observations", obs);
        return Digests.sha256(JsonCanonicalizer.canonicalBytes(m));
    }
}
