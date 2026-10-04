package io.lifeengine.cryptobot.benchmark;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.lifeengine.cryptobot.benchmark.AuthorityLayer.Result;
import io.lifeengine.cryptobot.benchmark.BenchmarkRun.Row;
import io.lifeengine.cryptobot.benchmark.CorpusGenerator.Klass;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes the evidence of a benchmark run to {@code target/benchmark/}: a JSON document (for
 * the vault and for a diff between runs) and a Markdown table (for the PR and the issue). The
 * numbers are the ones the paper asks for in §29 and §36, plus the per-stage latency and the
 * meter snapshot — nothing here is typed by hand.
 */
final class BenchmarkReport {

    private BenchmarkReport() {}

    static Map<String, Object> document(BenchmarkRun first, BenchmarkRun second, boolean validatorsAgree, Map<String, Object> extra) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("benchmark", "cryptobot adversarial intents v1 (paper §29/§30/§36)");
        d.put("generated_at", Instant.now().toString());
        d.put("seed", first.seed);
        d.put("policy_version", first.rules.version());
        d.put("policy_hash", first.rules.hash());
        d.put("policy", first.rules.canonicalMap());
        d.put("host", Map.of("java", System.getProperty("java.version"), "os", System.getProperty("os.name") + " " + System.getProperty("os.arch"),
                "cpus", Runtime.getRuntime().availableProcessors()));

        List<Row> valid = first.valid();
        List<Row> adv = first.adversarial();
        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("intents_generated", first.rows.size());
        counts.put("valid_generated", valid.size());
        counts.put("adversarial_generated", adv.size());
        counts.put("valid_authorized", valid.stream().filter(r -> r.o().authorized()).count());
        counts.put("valid_executed_autonomously", first.count(valid, Result.EXECUTED));
        counts.put("valid_escalated_pending", first.count(valid, Result.AUTHORIZED_PENDING));
        counts.put("valid_hold", first.count(valid, Result.NOOP));
        counts.put("valid_cancelled", first.count(valid, Result.CANCELLED));
        counts.put("valid_rejected", first.falseRejections().size());
        counts.put("adversarial_blocked", adv.stream().filter(r -> r.o().blocked()).count());
        counts.put("adversarial_executed", first.count(adv, Result.EXECUTED));
        counts.put("adversarial_authorized_pending", first.count(adv, Result.AUTHORIZED_PENDING));
        counts.put("policy_violations_executed", first.violations().size());
        counts.put("block_rate", first.blockRate());
        d.put("counts", counts);

        Map<String, Object> classes = new LinkedHashMap<>();
        Map<Klass, Long> total = first.byClassTotal();
        Map<Klass, Long> blocked = first.byClassBlocked();
        first.byClassAndReason().forEach((k, reasons) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("generated", total.get(k));
            row.put("blocked", blocked.get(k));
            row.put("outcomes", reasons);
            classes.put(k.name(), row);
        });
        d.put("by_class", classes);
        d.put("failed_predicates", first.failedPredicates());

        Map<String, Object> repro = new LinkedHashMap<>();
        repro.put("second_run_seed", second.seed);
        repro.put("verdict_hashes_identical", first.verdictHashes().equals(second.verdictHashes()));
        repro.put("outcomes_identical", outcomes(first).equals(outcomes(second)));
        repro.put("engine_and_reference_validator_agree_on_every_verdict", validatorsAgree);
        repro.put("verdicts_compared", first.verdictHashes().stream().filter(h -> !"-".equals(h)).count());
        d.put("reproducibility", repro);

        Map<String, Object> latency = new LinkedHashMap<>();
        latency.put("unit", "microseconds per intent, single thread, JIT-warm (second run)");
        Map<String, Object> stages = new LinkedHashMap<>();
        second.latency().forEach((stage, p) -> stages.put(stage, Map.of("p50", p.p50Us(), "p95", p.p95Us(), "p99", p.p99Us(), "max", p.maxUs())));
        latency.put("stages", stages);
        latency.put("wall_ms_first_run", first.wallNs / 1_000_000);
        latency.put("wall_ms_second_run", second.wallNs / 1_000_000);
        latency.put("inference", "not measured: the corpus is generated, not inferred — the generator plays the fully compromised agent (§29), so there is no LLM in the loop");
        latency.put("transaction", "not measured: execution is the harness stub (nonce consumed, exposure added); devnet latency lives in solana_confirmation_latency_seconds");
        d.put("latency", latency);

        d.put("cost", Map.of("llm_usd", 0, "chain_usd", 0, "note", "pure Java on the host; no model call, no RPC call, no signature on chain"));
        d.put("meters_first_run", first.meters());
        d.putAll(extra);
        return d;
    }

    static final Path DIR = Path.of("target", "benchmark");

    static void write(Map<String, Object> document, String name) throws IOException {
        Files.createDirectories(DIR);
        ObjectMapper json = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        Files.writeString(DIR.resolve(name + ".json"), json.writeValueAsString(document), StandardCharsets.UTF_8);
        Files.writeString(DIR.resolve(name + ".md"), markdown(document), StandardCharsets.UTF_8);
    }

    /** A section-only document (chaos, invariants): the same writer, without the corpus counts. */
    static void writeSection(String title, String key, Map<String, Object> section, String name) throws IOException {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("benchmark", title);
        d.put("generated_at", Instant.now().toString());
        d.put(key, section);
        Files.createDirectories(DIR);
        ObjectMapper json = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        Files.writeString(DIR.resolve(name + ".json"), json.writeValueAsString(d), StandardCharsets.UTF_8);
        StringBuilder sb = new StringBuilder("# " + title + "\n\n- generated: " + d.get("generated_at") + "\n");
        appendSection(sb, key, section);
        Files.writeString(DIR.resolve(name + ".md"), sb.toString(), StandardCharsets.UTF_8);
    }

    @SuppressWarnings("unchecked")
    static String markdown(Map<String, Object> d) {
        StringBuilder sb = new StringBuilder();
        Map<String, Object> c = (Map<String, Object>) d.get("counts");
        sb.append("# ").append(d.get("benchmark")).append("\n\n");
        sb.append("- generated: ").append(d.get("generated_at")).append(" · seed ").append(d.get("seed")).append(" · policy `")
                .append(d.get("policy_version")).append("` `").append(d.get("policy_hash")).append("`\n");
        sb.append("- host: ").append(d.get("host")).append("\n\n");
        sb.append("| metric | value |\n|---|---|\n");
        c.forEach((k, v) -> sb.append("| ").append(k).append(" | ").append(v instanceof Double x ? BenchmarkRun.fmt(x) : v).append(" |\n"));
        sb.append("\n## Adversarial classes (paper §29)\n\n| class | generated | blocked | how |\n|---|---|---|---|\n");
        ((Map<String, Object>) d.get("by_class")).forEach((k, v) -> {
            Map<String, Object> row = (Map<String, Object>) v;
            sb.append("| ").append(k).append(" | ").append(row.get("generated")).append(" | ").append(row.get("blocked")).append(" | ")
                    .append(row.get("outcomes")).append(" |\n");
        });
        sb.append("\n## Reproducibility (paper §29)\n\n");
        ((Map<String, Object>) d.get("reproducibility")).forEach((k, v) -> sb.append("- ").append(k).append(": ").append(v).append("\n"));
        sb.append("\n## Latency per stage (µs, ").append(((Map<String, Object>) d.get("latency")).get("unit")).append(")\n\n| stage | p50 | p95 | p99 | max |\n|---|---|---|---|---|\n");
        ((Map<String, Object>) ((Map<String, Object>) d.get("latency")).get("stages")).forEach((stage, p) -> {
            Map<String, Object> m = (Map<String, Object>) p;
            sb.append("| ").append(stage).append(" | ").append(m.get("p50")).append(" | ").append(m.get("p95")).append(" | ").append(m.get("p99"))
                    .append(" | ").append(m.get("max")).append(" |\n");
        });
        Map<String, Object> lat = (Map<String, Object>) d.get("latency");
        sb.append("\n- wall: first run ").append(lat.get("wall_ms_first_run")).append(" ms, second run ").append(lat.get("wall_ms_second_run")).append(" ms\n");
        sb.append("- inference: ").append(lat.get("inference")).append("\n- transaction: ").append(lat.get("transaction")).append("\n");
        sb.append("\n## Cost\n\n").append(d.get("cost")).append("\n");
        if (d.containsKey("compromised_agent_fuzz")) {
            sb.append("\n## Compromised agent — random mutations (paper §29)\n\n| metric | value |\n|---|---|\n");
            ((Map<String, Object>) d.get("compromised_agent_fuzz")).forEach((k, v) -> sb.append("| ").append(k).append(" | ").append(v).append(" |\n"));
        }
        sb.append("\n## Meters (first run — what the internal ticket funnel would show)\n\n| meter | count |\n|---|---|\n");
        ((Map<String, Object>) d.get("meters_first_run")).forEach((k, v) -> sb.append("| `").append(k).append("` | ").append(v).append(" |\n"));
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void appendSection(StringBuilder sb, String key, Map<String, Object> section) {
        if ("chaos".equals(key)) {
            sb.append("\n## Chaos (paper §30)\n\n| scenario | result | stage | reason |\n|---|---|---|---|\n");
            section.forEach((k, v) -> {
                Map<String, Object> m = (Map<String, Object>) v;
                sb.append("| ").append(k).append(" | ").append(m.get("result")).append(" | ").append(m.get("stage")).append(" | ").append(m.get("reason")).append(" |\n");
            });
        } else if ("invariants".equals(key)) {
            sb.append("\n## Invariants (paper §23, §17, §29)\n\n| invariant | statement | checked over | holds |\n|---|---|---|---|\n");
            section.forEach((k, v) -> {
                Map<String, Object> m = (Map<String, Object>) v;
                sb.append("| ").append(k).append(" | ").append(m.get("statement")).append(" | ").append(m.get("checked_over")).append(" | ").append(m.get("holds")).append(" |\n");
            });
        }
    }

    private static List<String> outcomes(BenchmarkRun run) {
        return run.rows.stream().map(r -> r.o().result() + "/" + r.o().stage() + "/" + r.o().reason()).toList();
    }
}
