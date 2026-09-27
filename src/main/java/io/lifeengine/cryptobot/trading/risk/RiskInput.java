package io.lifeengine.cryptobot.trading.risk;

import io.lifeengine.cryptobot.core.intent.JsonCanonicalizer;
import io.lifeengine.cryptobot.trading.portfolio.PortfolioDiff;
import io.lifeengine.cryptobot.trading.portfolio.PortfolioSnapshot;
import io.lifeengine.cryptobot.trading.portfolio.Position;
import io.lifeengine.cryptobot.core.receipts.Digests;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The canonical input of the deterministic risk engine (Endgame §5): <b>integers at the
 * frontier, nothing else</b>. Weights are basis points, values are micro-dollars, moves are basis
 * points; a position is identified by its mint and named by its symbol. The quantisation from
 * the valued snapshot ({@link #quantize}) is part of the model — it is versioned with the engine,
 * and its rounding is fixed ({@code HALF_UP}), so the same snapshot yields the same input bytes on
 * any host.
 *
 * <p>{@link #toMap()} is the value tree that is canonicalised (RFC 8785) and hashed into the
 * receipt's {@code RISK_INPUT}; {@link #fromMap(Map)} is its inverse, so a stored input re-executes
 * to the same output. Positions are sorted by mint: the order the wallet listed them in is not
 * part of the decision.
 *
 * <p>Schema {@code risk-input/1}.
 */
public record RiskInput(List<PositionInput> positions, long totalUsdMicros, Integer deltaBps, String largestMoveAsset) {

    public static final String SCHEMA = "risk-input/1";
    private static final BigDecimal BPS_PER_PCT = new BigDecimal("100");
    private static final BigDecimal MICROS_PER_USD = new BigDecimal("1000000");

    /**
     * One holding. {@code exposureBps} is its share of the valued total (0 when unpriced);
     * {@code valueUsdMicros} its value (0 when unpriced); {@code held} whether the amount is > 0.
     */
    public record PositionInput(String asset, String mint, int exposureBps, long valueUsdMicros, boolean held, boolean priced, boolean stable) {
        public PositionInput {
            asset = requireText("asset", asset);
            mint = requireText("mint", mint);
            if (exposureBps < 0 || exposureBps > 10_000) {
                throw new IllegalArgumentException("exposureBps out of range: " + exposureBps);
            }
            if (valueUsdMicros < 0 || valueUsdMicros > JsonCanonicalizer.MAX_SAFE_INTEGER) {
                throw new IllegalArgumentException("valueUsdMicros must be 0.." + JsonCanonicalizer.MAX_SAFE_INTEGER);
            }
        }

        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("asset", asset);
            m.put("mint", mint);
            m.put("exposureBps", exposureBps);
            m.put("valueUsdMicros", valueUsdMicros);
            m.put("held", held);
            m.put("priced", priced);
            m.put("stable", stable);
            return m;
        }
    }

    public RiskInput {
        List<PositionInput> sorted = new ArrayList<>(positions == null ? List.of() : positions);
        sorted.sort(Comparator.comparing(PositionInput::mint).thenComparing(PositionInput::asset));
        for (int i = 1; i < sorted.size(); i++) {
            if (sorted.get(i).mint().equals(sorted.get(i - 1).mint())) {
                throw new IllegalArgumentException("duplicate mint in risk input: " + sorted.get(i).mint());
            }
        }
        positions = List.copyOf(sorted);
        if (totalUsdMicros < 0 || totalUsdMicros > JsonCanonicalizer.MAX_SAFE_INTEGER) {
            // RFC 8785 numbers are IEEE doubles: an integer above 2^53 − 1 has no canonical form, so it has no hash.
            throw new IllegalArgumentException("totalUsdMicros must be 0.." + JsonCanonicalizer.MAX_SAFE_INTEGER);
        }
        largestMoveAsset = largestMoveAsset == null || largestMoveAsset.isBlank() ? null : largestMoveAsset.trim();
    }

    /** The quantisation of the model: percentages → basis points (HALF_UP), dollars → micro-dollars (HALF_UP). */
    public static RiskInput quantize(PortfolioSnapshot snapshot, PortfolioDiff diff) {
        Objects.requireNonNull(snapshot, "snapshot");
        List<PositionInput> positions = new ArrayList<>();
        for (Position p : snapshot.positions()) {
            boolean priced = p.priced();
            positions.add(new PositionInput(
                    p.symbol() == null || p.symbol().isBlank() ? p.mint() : p.symbol(),
                    p.mint(),
                    priced && p.weightPct() != null ? bps(p.weightPct()) : 0,
                    priced && p.valueUsd() != null ? micros(p.valueUsd()) : 0L,
                    p.amount() != null && p.amount().signum() > 0,
                    priced,
                    p.stable()));
        }
        long total = snapshot.totalUsd() == null ? 0L : micros(snapshot.totalUsd());
        Integer delta = diff == null || diff.totalUsdDeltaPct() == null ? null : bpsSigned(diff.totalUsdDeltaPct());
        String largest = diff == null ? null : diff.largestMoveSymbol();
        return new RiskInput(positions, total, delta, largest);
    }

    /** The canonical value tree ({@code risk-input/1}). Absent optionals are omitted, never {@code null}. */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("schema", SCHEMA);
        List<Map<String, Object>> ps = new ArrayList<>();
        for (PositionInput p : positions) {
            ps.add(p.toMap());
        }
        m.put("positions", ps);
        m.put("totalUsdMicros", totalUsdMicros);
        if (deltaBps != null) {
            m.put("deltaBps", deltaBps);
        }
        if (largestMoveAsset != null) {
            m.put("largestMoveAsset", largestMoveAsset);
        }
        return m;
    }

    /** {@code sha256:…} of the canonical bytes of {@link #toMap()}. */
    public String hash() {
        return Digests.sha256(canonicalBytes());
    }

    public byte[] canonicalBytes() {
        return JsonCanonicalizer.canonicalBytes(toMap());
    }

    @SuppressWarnings("unchecked")
    public static RiskInput fromMap(Map<String, Object> m) {
        String schema = (String) m.get("schema");
        if (!SCHEMA.equals(schema)) {
            throw new IllegalArgumentException("risk input schema " + schema + " is not " + SCHEMA);
        }
        List<PositionInput> positions = new ArrayList<>();
        for (Object o : (List<Object>) m.getOrDefault("positions", List.of())) {
            Map<String, Object> p = (Map<String, Object>) o;
            positions.add(new PositionInput((String) p.get("asset"), (String) p.get("mint"), intOf(p, "exposureBps"), longOf(p, "valueUsdMicros"),
                    bool(p, "held"), bool(p, "priced"), bool(p, "stable")));
        }
        Object delta = m.get("deltaBps");
        return new RiskInput(positions, longOf(m, "totalUsdMicros"), delta == null ? null : ((Number) delta).intValue(), (String) m.get("largestMoveAsset"));
    }

    static int bps(BigDecimal pct) {
        int v = pct.multiply(BPS_PER_PCT).setScale(0, RoundingMode.HALF_UP).intValueExact();
        return Math.max(0, Math.min(10_000, v));
    }

    static int bpsSigned(BigDecimal pct) {
        return pct.multiply(BPS_PER_PCT).setScale(0, RoundingMode.HALF_UP).intValueExact();
    }

    static long micros(BigDecimal usd) {
        return Math.max(0L, usd.multiply(MICROS_PER_USD).setScale(0, RoundingMode.HALF_UP).longValueExact());
    }

    private static String requireText(String field, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }

    private static int intOf(Map<String, Object> m, String key) {
        return ((Number) Objects.requireNonNull(m.get(key), key)).intValue();
    }

    private static long longOf(Map<String, Object> m, String key) {
        return ((Number) Objects.requireNonNull(m.get(key), key)).longValue();
    }

    private static boolean bool(Map<String, Object> m, String key) {
        return Boolean.TRUE.equals(m.get(key));
    }
}
