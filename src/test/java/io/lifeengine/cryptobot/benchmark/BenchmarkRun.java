package io.lifeengine.cryptobot.benchmark;

import io.lifeengine.cryptobot.benchmark.AuthorityLayer.Outcome;
import io.lifeengine.cryptobot.benchmark.AuthorityLayer.Result;
import io.lifeengine.cryptobot.benchmark.CorpusGenerator.Case;
import io.lifeengine.cryptobot.benchmark.CorpusGenerator.Klass;
import io.lifeengine.cryptobot.domain.policy.PolicyPredicate;
import io.lifeengine.cryptobot.domain.policy.PolicyRules;
import io.lifeengine.cryptobot.observability.CryptobotMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.LongUnaryOperator;

/**
 * One pass of a corpus through a fresh {@link AuthorityLayer}: the rows, the counts the paper
 * asks for (§29: BlockRate, reproducibility; §36: authorized / rejected / violations executed),
 * the per-stage latency distribution and the meters the Grafana funnel (KAN-425) would show.
 */
public final class BenchmarkRun {

    public record Row(Case c, Outcome o) {}

    public record Percentiles(long p50Us, long p95Us, long p99Us, long maxUs) {}

    public final long seed;
    public final PolicyRules rules;
    public final AuthorityLayer layer;
    public final SimpleMeterRegistry registry;
    public final List<Row> rows = new ArrayList<>();
    public final long wallNs;

    private BenchmarkRun(long seed, PolicyRules rules, AgentRegistry agents, List<Case> corpus) {
        this.seed = seed;
        this.rules = rules;
        this.registry = new SimpleMeterRegistry();
        this.layer = new AuthorityLayer(rules, agents, CorpusGenerator.oracle(), CorpusGenerator.START_SLOT,
                new CryptobotMetrics(registry, CorpusGenerator.ASSETS));
        long t0 = System.nanoTime();
        for (Case c : corpus) {
            rows.add(new Row(c, submit(c)));
        }
        this.wallNs = System.nanoTime() - t0;
    }

    /** Generates the corpus for {@code seed} and runs it. The registry is shared so signatures stay valid across runs. */
    public static BenchmarkRun of(long seed, int valid, int adversarial, AgentRegistry agents) {
        PolicyRules rules = CorpusGenerator.paperRules();
        List<Case> corpus = new CorpusGenerator(seed, rules, agents).generate(valid, adversarial);
        return new BenchmarkRun(seed, rules, agents, corpus);
    }

    public static AgentRegistry agents() {
        List<String> permitted = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            permitted.add(String.format("agent-%02d", i));
        }
        return AgentRegistry.of(permitted, List.of("agent-revoked-0", "agent-revoked-1"));
    }

    private Outcome submit(Case c) {
        layer.advanceSlots(c.advanceSlots());
        Long age = layer.oracle().ageSeconds();
        if (c.oracleAge() != null) {
            layer.oracle().ageSeconds(c.oracleAge());
        }
        if (c.manipulatedAsset() != null) {
            layer.oracle().secondary(c.manipulatedAsset(), c.manipulatedCents());
        }
        // KAN-572: the source-level attacks — every source stale, or a single source answering.
        layer.oracle().staleSources(c.klass() == Klass.STALE_PRICE_SOURCES);
        layer.oracle().singleSource(c.klass() == Klass.SINGLE_PRICE_SOURCE);
        try {
            return layer.submit(c.json(), c.signature());
        } finally {
            layer.oracle().ageSeconds(age);
            layer.oracle().staleSources(false);
            layer.oracle().singleSource(false);
            if (c.manipulatedAsset() != null) {
                layer.oracle().restoreSecondary(c.manipulatedAsset());
            }
        }
    }

    // ---- counts -------------------------------------------------------------------------------

    public List<Row> valid() {
        return rows.stream().filter(r -> !r.c().klass().adversarial()).toList();
    }

    public List<Row> adversarial() {
        return rows.stream().filter(r -> r.c().klass().adversarial()).toList();
    }

    public long count(List<Row> rows, Result result) {
        return rows.stream().filter(r -> r.o().result() == result).count();
    }

    /** {@code InvalidIntentsBlocked / InvalidIntentsGenerated} (§29). */
    public double blockRate() {
        List<Row> adv = adversarial();
        return adv.isEmpty() ? Double.NaN : (double) adv.stream().filter(r -> r.o().blocked()).count() / adv.size();
    }

    /** Adversarial intents that were not stopped: executed, or authorized to wait for a human. Must be empty. */
    public List<Row> violations() {
        return adversarial().stream().filter(r -> r.o().authorized()).toList();
    }

    /** Valid intents the layer refused: must be empty for the corpus to be the paper's 7 000. */
    public List<Row> falseRejections() {
        return valid().stream().filter(r -> !r.o().authorized()).toList();
    }

    /** The verdict hash of every row that reached the policy, in order — the reproducibility fingerprint of a run. */
    public List<String> verdictHashes() {
        return rows.stream().map(r -> r.o().verdict() == null ? "-" : r.o().verdict().hash()).toList();
    }

    public Map<Klass, Map<String, Long>> byClassAndReason() {
        Map<Klass, Map<String, Long>> out = new EnumMap<>(Klass.class);
        for (Row r : rows) {
            String reason = switch (r.o().stage()) {
                case SCHEMA -> "SCHEMA";
                case POLICY -> "POLICY_UNAVAILABLE";
                case GATE, EXECUTION -> r.o().verdict() == null ? r.o().result().name()
                        : r.o().verdict().denied() ? r.o().reason() : r.o().result().name();
                default -> r.o().stage().name();
            };
            out.computeIfAbsent(r.c().klass(), k -> new TreeMap<>()).merge(reason, 1L, Long::sum);
        }
        return out;
    }

    public Map<Klass, Long> byClassBlocked() {
        Map<Klass, Long> out = new EnumMap<>(Klass.class);
        for (Row r : rows) {
            out.merge(r.c().klass(), r.o().blocked() ? 1L : 0L, Long::sum);
        }
        return out;
    }

    public Map<Klass, Long> byClassTotal() {
        Map<Klass, Long> out = new EnumMap<>(Klass.class);
        for (Row r : rows) {
            out.merge(r.c().klass(), 1L, Long::sum);
        }
        return out;
    }

    public Map<PolicyPredicate, Long> failedPredicates() {
        Map<PolicyPredicate, Long> out = new EnumMap<>(PolicyPredicate.class);
        for (Row r : rows) {
            if (r.o().verdict() != null) {
                r.o().verdict().failedPredicates().forEach(p -> out.merge(p, 1L, Long::sum));
            }
        }
        return out;
    }

    // ---- latency -----------------------------------------------------------------------------

    public Map<String, Percentiles> latency() {
        Map<String, Percentiles> out = new LinkedHashMap<>();
        out.put("schema", percentiles(t -> t.parseNs()));
        out.put("authentication", percentiles(t -> t.authNs()));
        out.put("state", percentiles(t -> t.stateNs()));
        out.put("policy", percentiles(t -> t.policyNs()));
        out.put("validation", percentiles(t -> t.validateNs()));
        out.put("execution", percentiles(t -> t.executeNs()));
        out.put("total", percentiles(t -> t.totalNs()));
        return out;
    }

    private Percentiles percentiles(java.util.function.ToLongFunction<AuthorityLayer.Timings> f) {
        long[] v = rows.stream().mapToLong(r -> f.applyAsLong(r.o().timings())).sorted().toArray();
        LongUnaryOperator at = q -> v[(int) Math.min(v.length - 1, Math.max(0, (q * v.length) / 100))] / 1_000;
        return new Percentiles(at.applyAsLong(50), at.applyAsLong(95), at.applyAsLong(99), v[v.length - 1] / 1_000);
    }

    // ---- meters (what the Grafana funnel shows) -------------------------------------------------

    public Map<String, Double> meters() {
        Map<String, Double> out = new TreeMap<>();
        for (var m : registry.getMeters()) {
            if (m instanceof Counter c && c.count() > 0) {
                StringBuilder tags = new StringBuilder();
                m.getId().getTags().forEach(t -> tags.append(tags.length() == 0 ? "{" : ",").append(t.getKey()).append("=").append(t.getValue()));
                if (tags.length() > 0) {
                    tags.append('}');
                }
                out.put(m.getId().getName() + tags, c.count());
            }
        }
        return out;
    }

    static String fmt(double v) {
        return String.format(java.util.Locale.ROOT, "%.4f", v);
    }

    static List<Klass> attackClasses() {
        return Arrays.stream(Klass.values()).filter(Klass::adversarial).toList();
    }
}
