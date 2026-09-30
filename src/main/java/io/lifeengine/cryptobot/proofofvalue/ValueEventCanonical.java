package io.lifeengine.cryptobot.proofofvalue;

import io.lifeengine.cryptobot.core.intent.JsonCanonicalizer;
import io.lifeengine.cryptobot.core.receipts.Digests;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The value trees of a ValueEvent and their hashes. Canonical JSON is RFC 8785 (the same
 * {@link JsonCanonicalizer} the intents and the receipts use); every hash is a plain
 * {@code sha256:<hex>} of those bytes, so {@code sha256sum} over the stored {@code canonical}
 * recomputes it. Absent optional fields are omitted, never {@code null}; timestamps are
 * {@link Instant#toString()}.
 *
 * <pre>
 *   artifactHash   = sha256(JCS(artifact))
 *   acceptanceHash = sha256(JCS(acceptance))
 *   valueEventHash = sha256(JCS(event))   ← the VALUE_EVENT receipt's output.hash
 * </pre>
 *
 * <p>an internal ticket: an event with knowledge assets or compute receipts is {@link #SCHEMA_V2}; its {@code knowledgeAssets}
 * and {@code computeReceipts} carry the expanded objects (asset id, version, kind, title, creator, content hash; provider,
 * provider wallet, node, model, tokens, GPU milliseconds, estimated cost in micro-USD), so the hash anchored on-chain
 * covers them. An event without either stays {@link #SCHEMA} byte for byte — V1 hashes and idempotency do not move.
 */
public final class ValueEventCanonical {

    public static final String SCHEMA = "pov/value-event/v1";
    public static final String SCHEMA_V2 = "pov/value-event/v2";

    private ValueEventCanonical() {}

    public record Artifact(String commitSha, String prUrl, String imageDigest) {}

    public record Acceptance(String source, String environment, Map<String, Boolean> stages, String evidenceRef, Instant acceptedAt) {}

    /** {@code derivedFrom}: {@code null} when the contribution came in the request; the asset ids when it was added for their creator. */
    public record Contribution(String identityId, ContributionRole role, int units, List<String> derivedFrom) {
        public Contribution(String identityId, ContributionRole role, int units) {
            this(identityId, role, units, null);
        }
    }

    public record KnowledgeRef(String id, int version, String kind, String title, String creatorId, String contentHash) {}

    public record Compute(String providerId, String providerWallet, String node, String model, long inputTokens, long outputTokens, long gpuMillis,
            long estimatedCostMicroUsd) {}

    public static String schemaFor(List<KnowledgeRef> knowledge, List<Compute> compute) {
        return (knowledge == null || knowledge.isEmpty()) && (compute == null || compute.isEmpty()) ? SCHEMA : SCHEMA_V2;
    }

    public static Map<String, Object> artifactTree(Artifact a) {
        Map<String, Object> m = new LinkedHashMap<>();
        put(m, "commitSha", a.commitSha());
        put(m, "prUrl", a.prUrl());
        put(m, "imageDigest", a.imageDigest());
        return m;
    }

    /** Only the five stages of {@link AcceptancePolicy#STAGES} are part of the tree; unknown keys are not evidence. */
    public static Map<String, Object> acceptanceTree(Acceptance a) {
        Map<String, Object> stages = new LinkedHashMap<>();
        for (String s : AcceptancePolicy.STAGES) {
            Boolean v = a.stages() == null ? null : a.stages().get(s);
            if (v != null) {
                stages.put(s, v);
            }
        }
        Map<String, Object> m = new LinkedHashMap<>();
        put(m, "source", a.source());
        put(m, "environment", a.environment());
        m.put("stages", stages);
        put(m, "evidenceRef", a.evidenceRef());
        put(m, "acceptedAt", a.acceptedAt() == null ? null : a.acceptedAt().toString());
        return m;
    }

    public static String hash(Map<String, Object> tree) {
        return Digests.sha256(JsonCanonicalizer.canonicalBytes(tree));
    }

    public static String canonical(Map<String, Object> tree) {
        return JsonCanonicalizer.canonicalize(tree);
    }

    /** The whole event: what the receipt commits to. Contributions keep the request order (it decides the remainder). */
    public static Map<String, Object> eventTree(String tenantId, String projectId, String taskId, String title, Artifact artifact, Acceptance acceptance,
            List<Contribution> contributions, List<KnowledgeRef> knowledgeAssets, List<Compute> computeReceipts, String distributionPolicy, int totalUnits) {
        Map<String, Object> artifactTree = artifactTree(artifact);
        Map<String, Object> acceptanceTree = acceptanceTree(acceptance);
        List<Map<String, Object>> cs = new ArrayList<>();
        for (Contribution c : contributions) {
            Map<String, Object> cm = new LinkedHashMap<>();
            cm.put("identityId", c.identityId());
            cm.put("role", c.role().name());
            cm.put("units", c.units());
            if (c.derivedFrom() != null && !c.derivedFrom().isEmpty()) {
                cm.put("derivedFrom", List.copyOf(c.derivedFrom()));
            }
            cs.add(cm);
        }
        List<Map<String, Object>> ks = new ArrayList<>();
        for (KnowledgeRef k : knowledgeAssets == null ? List.<KnowledgeRef>of() : knowledgeAssets) {
            Map<String, Object> km = new LinkedHashMap<>();
            km.put("id", k.id());
            km.put("version", k.version());
            km.put("kind", k.kind());
            km.put("title", k.title());
            km.put("creatorId", k.creatorId());
            km.put("contentHash", k.contentHash());
            ks.add(km);
        }
        List<Map<String, Object>> rs = new ArrayList<>();
        for (Compute r : computeReceipts == null ? List.<Compute>of() : computeReceipts) {
            Map<String, Object> rm = new LinkedHashMap<>();
            rm.put("providerId", r.providerId());
            rm.put("providerWallet", r.providerWallet());
            rm.put("node", r.node());
            rm.put("model", r.model());
            rm.put("inputTokens", r.inputTokens());
            rm.put("outputTokens", r.outputTokens());
            rm.put("gpuMillis", r.gpuMillis());
            rm.put("estimatedCostMicroUsd", r.estimatedCostMicroUsd());
            rs.add(rm);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("schema", schemaFor(knowledgeAssets, computeReceipts));
        m.put("tenantId", tenantId);
        m.put("projectId", projectId);
        m.put("taskId", taskId);
        m.put("title", title);
        m.put("artifact", artifactTree);
        m.put("artifactHash", hash(artifactTree));
        m.put("acceptance", acceptanceTree);
        m.put("acceptanceHash", hash(acceptanceTree));
        m.put("acceptancePolicy", AcceptancePolicy.ID);
        m.put("contributions", cs);
        m.put("knowledgeAssets", ks);
        m.put("computeReceipts", rs);
        m.put("distributionPolicy", distributionPolicy);
        m.put("totalUnits", totalUnits);
        return m;
    }

    private static void put(Map<String, Object> m, String k, Object v) {
        if (v != null) {
            m.put(k, v);
        }
    }
}
