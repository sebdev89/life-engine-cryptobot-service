package io.lifeengine.cryptobot.domain.oracle;

import io.lifeengine.cryptobot.domain.intent.JsonCanonicalizer;
import io.lifeengine.cryptobot.domain.receipt.Digests;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The oracle's answer for the set of assets one decision depends on (KAN-439): one
 * {@link OracleConsensus} per asset, the limits they were judged under, and the instant they
 * were read. It travels with the {@code PolicyDecision} and is what the receipt's state
 * reference points at: {@link #quotesHash()} covers every quote of every asset; {@link #limitsHash()}
 * the integrity assumptions.
 *
 * <p>{@link #asOf()} is the oldest accepted observation across the assets — the oracle age of the
 * decision — and is empty as soon as one asset is not accepted: an unknown price makes the whole
 * state unknown.
 */
public record OracleReading(Instant readAt, OracleLimits limits, List<OracleConsensus> assets) {

    public static final String SCHEMA_VERSION = "oracle-reading/1";

    public OracleReading {
        if (readAt == null) {
            throw new IllegalArgumentException("readAt: missing");
        }
        if (limits == null) {
            throw new IllegalArgumentException("limits: missing");
        }
        assets = assets == null ? List.of() : assets.stream().sorted(Comparator.comparing(OracleConsensus::asset)).toList();
    }

    public Optional<OracleConsensus> of(String asset) {
        if (asset == null) {
            return Optional.empty();
        }
        String wanted = asset.trim().toUpperCase(java.util.Locale.ROOT);
        return assets.stream().filter(c -> c.asset().equals(wanted)).findFirst();
    }

    /** Every asset read reached an accepted consensus. An empty reading is accepted (nothing to price). */
    public boolean accepted() {
        return assets.stream().allMatch(OracleConsensus::accepted);
    }

    /** Oldest accepted observation across the assets; empty if any asset is not accepted. */
    public Optional<Instant> asOf() {
        if (!accepted()) {
            return Optional.empty();
        }
        return assets.stream().map(OracleConsensus::asOf).min(Comparator.naturalOrder());
    }

    public List<String> problems() {
        List<String> out = new ArrayList<>();
        for (OracleConsensus c : assets) {
            c.problems().forEach(p -> out.add(c.asset() + ": " + p));
        }
        return out;
    }

    public String limitsHash() {
        return limits.hash();
    }

    /** {@code oracle-reading/1}: the per-asset quote hashes, in asset order — the state reference of the decision. */
    public String quotesHash() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("schema_version", SCHEMA_VERSION);
        m.put("limits_hash", limits.hash());
        List<Map<String, Object>> per = new ArrayList<>();
        for (OracleConsensus c : assets) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("asset", c.asset());
            e.put("quotes_hash", c.quotesHash());
            e.put("accepted", c.accepted());
            per.add(e);
        }
        m.put("assets", per);
        return Digests.sha256(JsonCanonicalizer.canonicalBytes(m));
    }
}
