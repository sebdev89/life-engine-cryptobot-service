package io.lifeengine.cryptobot.application.chaos;

import io.lifeengine.cryptobot.core.oracle.PriceObservation;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * KAN-572 — the adversarial price of the demo: what the oracle's sources say is tampered with
 * <em>after</em> they answered, so the real {@code PriceOracle} refuses for a real reason. Same
 * contract as {@link BroadcastChaos}: exists only with {@code cryptobot.chaos.enabled=true}
 * (never UAT/PROD), armed by {@code PUT /api/cryptobot/demo/price} or {@code CRYPTOBOT_DEMO_PRICE_OVERRIDE}
 * at startup, and every injection is logged so the evidence can quote it.
 *
 * <p>One override per asset. {@code source} is one source id ({@code pyth-hermes}) or {@code *}
 * for every enabled source; a source that did not answer gets an observation invented for it (the
 * demo does not depend on which real feeds are up). The price is absolute ({@code priceUsd}) or
 * relative ({@code factor} × what the source said, or × the median of the others when it did not
 * answer). {@code ageSeconds} back-dates the observation so it is stale.
 *
 * <ul>
 *   <li>{@code {asset:SOL, source:pyth-hermes, factor:0.1}} — one source says −90 %: {@code PRICE_DEVIATION};
 *   <li>{@code {asset:SOL, source:*, ageSeconds:900}} — every source is 15 min old: {@code PRICE_STALE};
 *   <li>{@code {asset:SOL, source:*, factor:0.1}} — the whole market "crashes" −90 % between two
 *       readings: {@code PRICE_CIRCUIT_BREAKER} (and {@code PRICE_DRIFT} against the plan's price).
 * </ul>
 */
public final class PriceChaos {

    /** What is armed for one asset. */
    public record Override(String asset, String source, BigDecimal priceUsd, BigDecimal factor, Long ageSeconds) {
        public Override {
            if (asset == null || asset.isBlank()) {
                throw new IllegalArgumentException("asset: missing");
            }
            asset = asset.trim().toUpperCase(Locale.ROOT);
            source = source == null || source.isBlank() ? ALL_SOURCES : source.trim();
            if (priceUsd != null && priceUsd.signum() <= 0) {
                throw new IllegalArgumentException("priceUsd: must be positive");
            }
            if (factor != null && factor.signum() <= 0) {
                throw new IllegalArgumentException("factor: must be positive");
            }
            if (ageSeconds != null && ageSeconds < 0) {
                throw new IllegalArgumentException("ageSeconds: must be non-negative");
            }
            if (priceUsd == null && factor == null && ageSeconds == null) {
                throw new IllegalArgumentException("nothing to inject: give priceUsd, factor or ageSeconds");
            }
        }

        public boolean everySource() {
            return ALL_SOURCES.equals(source);
        }

        /**
         * Parses {@code SOL:pyth-hermes:factor=0.1[:age=900]} / {@code SOL:*:price=18} / {@code SOL:*:age=900}
         * (env {@code CRYPTOBOT_DEMO_PRICE_OVERRIDE}); several overrides separated by {@code ;}.
         */
        public static List<Override> parse(String spec) {
            List<Override> out = new ArrayList<>();
            if (spec == null || spec.isBlank()) {
                return out;
            }
            for (String one : spec.split(";")) {
                if (one.isBlank()) {
                    continue;
                }
                String[] parts = one.trim().split(":");
                if (parts.length < 3) {
                    throw new IllegalArgumentException("price override: expected ASSET:SOURCE:key=value[:key=value], got '" + one.trim() + "'");
                }
                BigDecimal price = null;
                BigDecimal factor = null;
                Long age = null;
                for (int i = 2; i < parts.length; i++) {
                    String[] kv = parts[i].split("=", 2);
                    if (kv.length != 2) {
                        throw new IllegalArgumentException("price override: expected key=value, got '" + parts[i] + "'");
                    }
                    switch (kv[0].trim().toLowerCase(Locale.ROOT)) {
                        case "price", "priceusd" -> price = new BigDecimal(kv[1].trim());
                        case "factor" -> factor = new BigDecimal(kv[1].trim());
                        case "age", "ageseconds" -> age = Long.parseLong(kv[1].trim());
                        default -> throw new IllegalArgumentException("price override: unknown key '" + kv[0] + "' (price | factor | age)");
                    }
                }
                out.add(new Override(parts[0], parts[1], price, factor, age));
            }
            return out;
        }
    }

    /** One injection as it happened: which observation was replaced or invented, and what the oracle then saw. */
    public record Injection(Instant at, String asset, String source, String realPriceUsd, String injectedPriceUsd, Instant observedAt) {}

    public static final String ALL_SOURCES = "*";

    private final Map<String, Override> overrides = new ConcurrentHashMap<>();
    private final List<Injection> injections = new ArrayList<>();

    public synchronized Override arm(Override o) {
        overrides.put(o.asset(), o);
        injections.clear();
        return o;
    }

    public synchronized Optional<Override> disarm(String asset) {
        return Optional.ofNullable(overrides.remove(asset == null ? "" : asset.trim().toUpperCase(Locale.ROOT)));
    }

    public synchronized void disarmAll() {
        overrides.clear();
    }

    public synchronized boolean armed() {
        return !overrides.isEmpty();
    }

    public synchronized List<Override> overrides() {
        return overrides.values().stream().sorted(Comparator.comparing(Override::asset)).toList();
    }

    public synchronized List<Injection> injections() {
        return List.copyOf(injections);
    }

    /**
     * The observations of {@code asset} as the oracle will see them: untouched when nothing is
     * armed for it; otherwise the targeted source's observation replaced (or invented), the rest kept.
     *
     * @param enabledSources every source the oracle asked — {@code *} invents one per source that did not answer
     */
    public synchronized List<PriceObservation> apply(String asset, String mint, List<PriceObservation> real, List<String> enabledSources, Instant now) {
        Override o = overrides.get(asset == null ? "" : asset.trim().toUpperCase(Locale.ROOT));
        if (o == null) {
            return real;
        }
        Map<String, PriceObservation> bySource = new LinkedHashMap<>();
        for (PriceObservation obs : real) {
            bySource.putIfAbsent(obs.source(), obs);
        }
        List<String> targets = o.everySource() ? new ArrayList<>(enabledSources) : List.of(o.source());
        if (o.everySource()) {
            bySource.keySet().forEach(s -> {
                if (!targets.contains(s)) {
                    targets.add(s);
                }
            });
        }
        BigDecimal medianOfOthers = median(real.stream().filter(x -> !targets.contains(x.source())).map(PriceObservation::priceUsd).filter(p -> p != null).sorted().toList());
        if (medianOfOthers == null) {
            medianOfOthers = median(real.stream().map(PriceObservation::priceUsd).filter(p -> p != null).sorted().toList());
        }
        List<PriceObservation> out = new ArrayList<>();
        for (PriceObservation obs : real) {
            if (!targets.contains(obs.source())) {
                out.add(obs);
            }
        }
        for (String source : targets) {
            PriceObservation was = bySource.get(source);
            BigDecimal base = was != null && was.priceUsd() != null ? was.priceUsd() : medianOfOthers;
            BigDecimal price = o.priceUsd() != null ? o.priceUsd() : o.factor() != null && base != null ? base.multiply(o.factor()).setScale(8, RoundingMode.HALF_UP) : base;
            Instant observedAt = o.ageSeconds() != null ? now.minusSeconds(o.ageSeconds()) : was != null && was.observedAt() != null ? was.observedAt() : now;
            if (price == null) {
                continue; // nothing real to derive a price from and no absolute price: the source stays silent
            }
            out.add(new PriceObservation(source, asset, mint, price, observedAt));
            injections.add(new Injection(now, asset, source, was == null || was.priceUsd() == null ? null : was.priceUsd().stripTrailingZeros().toPlainString(),
                    price.stripTrailingZeros().toPlainString(), observedAt));
            if (injections.size() > 200) {
                injections.remove(0);
            }
        }
        return out;
    }

    private static BigDecimal median(List<BigDecimal> ascending) {
        int n = ascending.size();
        if (n == 0) {
            return null;
        }
        if (n % 2 == 1) {
            return ascending.get(n / 2);
        }
        return ascending.get(n / 2 - 1).add(ascending.get(n / 2)).divide(BigDecimal.valueOf(2), 8, RoundingMode.HALF_UP);
    }
}
