package io.lifeengine.cryptobot.domain.receipt;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * The signed part of an {@link IntelligenceReceipt} (schema {@code ir/1}, Endgame §6). Everything
 * here is either a hash, an identifier, a version, a count or a timestamp: <b>no prompt, no
 * answer, no chunk, no key ever appears in a body</b>. The one field that could leak a short
 * guessable text — the user's question — is a salted commitment ({@link Digests#commitment}).
 *
 * <h2>Canonical form</h2>
 *
 * {@link #toMap()} is the exact value tree that is canonicalised (RFC 8785 via
 * {@code JsonCanonicalizer}) and hashed. Rules that make the tree unambiguous:
 *
 * <ul>
 *   <li>Absent optional fields are <em>omitted</em>, never {@code null}.
 *   <li>Every number is an integer within the safe range; money and decimals travel as strings.
 *   <li>{@code parents} is sorted lexicographically; {@code inputs} by {@code (type, hash)}.
 *   <li>Timestamps are ISO-8601 UTC strings exactly as {@link Instant#toString()} renders them.
 *   <li>{@code params} is a flat string→(string|integer|boolean) map; keys sorted by the canonicaliser.
 * </ul>
 *
 * <p>{@link #fromMap(Map)} is the inverse, so a body read back from the {@code body} JSONB column
 * re-canonicalises to the same bytes (that is what {@code verify} checks).
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record ReceiptBody(
        String schemaVersion,
        ReceiptKind kind,
        String tenantId,
        String ownerId,
        String agentId,
        List<String> parents,
        List<ReceiptInput> inputs,
        String promptHash,
        Model model,
        Engine engine,
        RuntimeRef runtime,
        Map<String, Object> params,
        Output output,
        Compute compute,
        Cost cost,
        ReproducibilityLevel reproducibility,
        Instant startedAt,
        Instant completedAt,
        String nonce,
        Refs refs) {

    public static final String SCHEMA_VERSION = "ir/1";

    /** Which model answered, as the provider names it. {@code weightsHash} only when we computed it ourselves. */
    public record Model(String ref, String provider, String providerDigest, String weightsHash) {}

    /** The deterministic engine (L1) that produced the output: id, version and the hash of its rules. */
    public record Engine(String id, String version, String weightsHash) {}

    /** Where it ran: the Runtime run (if any), and the digests/versions of the services involved. */
    public record RuntimeRef(String runId, String runtimeDigest, String serviceDigest, String serviceVersion, String serviceCommit) {}

    /** The artifact produced: its hash, the schema it follows, and where the service keeps it. */
    public record Output(String hash, String schema, String artifactRef) {}

    /** Measured, not estimated. {@code units = inputTokens + 3·outputTokens} for an LLM step, 1 for the deterministic engine (§8). */
    public record Compute(Long inputTokens, Long outputTokens, long units, Long wallMs) {}

    /** {@code usd} is a decimal string; the price table is versioned because prices change and measurements do not. */
    public record Cost(String usd, String priceTableVersion) {}

    /** What the receipt is about, for lineage lookups. Ids only. */
    public record Refs(String walletId, String proposalId, String snapshotId) {}

    public ReceiptBody {
        schemaVersion = schemaVersion == null ? SCHEMA_VERSION : schemaVersion;
        Objects.requireNonNull(kind, "kind");
        tenantId = requireText("tenantId", tenantId);
        ownerId = requireText("ownerId", ownerId);
        agentId = requireText("agentId", agentId);
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(reproducibility, "reproducibility");
        Objects.requireNonNull(startedAt, "startedAt");
        Objects.requireNonNull(completedAt, "completedAt");
        nonce = requireText("nonce", nonce);
        List<String> sortedParents = new ArrayList<>();
        for (String p : parents == null ? List.<String>of() : parents) {
            String h = Digests.requireHash("parents[]", p);
            if (!sortedParents.contains(h)) {
                sortedParents.add(h);
            }
        }
        sortedParents.sort(null);
        parents = List.copyOf(sortedParents);
        List<ReceiptInput> sortedInputs = new ArrayList<>(inputs == null ? List.of() : inputs);
        sortedInputs.sort(Comparator.comparing(ReceiptInput::type).thenComparing(ReceiptInput::hash));
        inputs = List.copyOf(sortedInputs);
        promptHash = promptHash == null ? null : Digests.requireHash("promptHash", promptHash);
        params = params == null ? Map.of() : Map.copyOf(new TreeMap<>(params));
        Digests.requireHash("output.hash", output.hash());
    }

    private static String requireText(String field, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }

    /** The canonical value tree: what gets hashed and what is stored in {@code body}. */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("schemaVersion", schemaVersion);
        m.put("kind", kind.name());
        m.put("tenantId", tenantId);
        m.put("ownerId", ownerId);
        m.put("agentId", agentId);
        m.put("parents", parents);
        List<Map<String, Object>> in = new ArrayList<>();
        for (ReceiptInput i : inputs) {
            in.add(ordered("type", i.type(), "hash", i.hash()));
        }
        m.put("inputs", in);
        putIfPresent(m, "promptHash", promptHash);
        if (model != null) {
            m.put("model", ordered("ref", model.ref(), "provider", model.provider(), "providerDigest", model.providerDigest(), "weightsHash", model.weightsHash()));
        }
        if (engine != null) {
            m.put("engine", ordered("id", engine.id(), "version", engine.version(), "weightsHash", engine.weightsHash()));
        }
        if (runtime != null) {
            m.put("runtime", ordered("runId", runtime.runId(), "runtimeDigest", runtime.runtimeDigest(), "serviceDigest", runtime.serviceDigest(),
                    "serviceVersion", runtime.serviceVersion(), "serviceCommit", runtime.serviceCommit()));
        }
        if (!params.isEmpty()) {
            m.put("params", new TreeMap<>(params));
        }
        m.put("output", ordered("hash", output.hash(), "schema", output.schema(), "artifactRef", output.artifactRef()));
        if (compute != null) {
            m.put("compute", ordered("inputTokens", compute.inputTokens(), "outputTokens", compute.outputTokens(), "units", compute.units(), "wallMs", compute.wallMs()));
        }
        if (cost != null) {
            m.put("cost", ordered("usd", cost.usd(), "priceTableVersion", cost.priceTableVersion()));
        }
        m.put("reproducibility", reproducibility.name());
        m.put("startedAt", startedAt.toString());
        m.put("completedAt", completedAt.toString());
        m.put("nonce", nonce);
        if (refs != null) {
            Map<String, Object> r = ordered("walletId", refs.walletId(), "proposalId", refs.proposalId(), "snapshotId", refs.snapshotId());
            if (!r.isEmpty()) {
                m.put("refs", r);
            }
        }
        return m;
    }

    /** Inverse of {@link #toMap()}: the body as read back from the store. */
    @SuppressWarnings("unchecked")
    public static ReceiptBody fromMap(Map<String, Object> m) {
        List<ReceiptInput> inputs = new ArrayList<>();
        for (Object o : (List<Object>) m.getOrDefault("inputs", List.of())) {
            Map<String, Object> i = (Map<String, Object>) o;
            inputs.add(new ReceiptInput(text(i, "type"), text(i, "hash")));
        }
        Map<String, Object> model = (Map<String, Object>) m.get("model");
        Map<String, Object> engine = (Map<String, Object>) m.get("engine");
        Map<String, Object> runtime = (Map<String, Object>) m.get("runtime");
        Map<String, Object> output = (Map<String, Object>) m.get("output");
        Map<String, Object> compute = (Map<String, Object>) m.get("compute");
        Map<String, Object> cost = (Map<String, Object>) m.get("cost");
        Map<String, Object> refs = (Map<String, Object>) m.get("refs");
        return new ReceiptBody(
                text(m, "schemaVersion"),
                ReceiptKind.valueOf(text(m, "kind")),
                text(m, "tenantId"),
                text(m, "ownerId"),
                text(m, "agentId"),
                (List<String>) m.getOrDefault("parents", List.of()),
                inputs,
                text(m, "promptHash"),
                model == null ? null : new Model(text(model, "ref"), text(model, "provider"), text(model, "providerDigest"), text(model, "weightsHash")),
                engine == null ? null : new Engine(text(engine, "id"), text(engine, "version"), text(engine, "weightsHash")),
                runtime == null ? null : new RuntimeRef(text(runtime, "runId"), text(runtime, "runtimeDigest"), text(runtime, "serviceDigest"),
                        text(runtime, "serviceVersion"), text(runtime, "serviceCommit")),
                (Map<String, Object>) m.getOrDefault("params", Map.of()),
                new Output(text(output, "hash"), text(output, "schema"), text(output, "artifactRef")),
                compute == null ? null : new Compute(integer(compute, "inputTokens"), integer(compute, "outputTokens"),
                        Objects.requireNonNull(integer(compute, "units"), "compute.units"), integer(compute, "wallMs")),
                cost == null ? null : new Cost(text(cost, "usd"), text(cost, "priceTableVersion")),
                ReproducibilityLevel.valueOf(text(m, "reproducibility")),
                Instant.parse(text(m, "startedAt")),
                Instant.parse(text(m, "completedAt")),
                text(m, "nonce"),
                refs == null ? null : new Refs(text(refs, "walletId"), text(refs, "proposalId"), text(refs, "snapshotId")));
    }

    private static String text(Map<String, Object> m, String key) {
        Object v = m == null ? null : m.get(key);
        return v == null ? null : v.toString();
    }

    private static Long integer(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v == null ? null : ((Number) v).longValue();
    }

    private static Map<String, Object> ordered(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            putIfPresent(m, (String) kv[i], kv[i + 1]);
        }
        return m;
    }

    private static void putIfPresent(Map<String, Object> m, String key, Object value) {
        if (value != null) {
            m.put(key, value);
        }
    }
}
