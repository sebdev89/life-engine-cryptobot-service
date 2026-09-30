package io.lifeengine.cryptobot.proofofvalue;

import io.lifeengine.cryptobot.core.intent.JsonCanonicalizer;
import io.lifeengine.cryptobot.core.receipts.Digests;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The value trees of a ValueEvent and their hashes (KAN-818). Canonical JSON is RFC 8785 (the same
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
 */
public final class ValueEventCanonical {

    public static final String SCHEMA = "pov/value-event/v1";

    private ValueEventCanonical() {}

    public record Artifact(String commitSha, String prUrl, String imageDigest) {}

    public record Acceptance(String source, String environment, Map<String, Boolean> stages, String evidenceRef, Instant acceptedAt) {}

    public record Contribution(String identityId, ContributionRole role, int units) {}

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
            List<Contribution> contributions, List<String> knowledgeAssets, List<String> computeReceipts, String distributionPolicy, int totalUnits) {
        Map<String, Object> artifactTree = artifactTree(artifact);
        Map<String, Object> acceptanceTree = acceptanceTree(acceptance);
        List<Map<String, Object>> cs = new ArrayList<>();
        for (Contribution c : contributions) {
            Map<String, Object> cm = new LinkedHashMap<>();
            cm.put("identityId", c.identityId());
            cm.put("role", c.role().name());
            cm.put("units", c.units());
            cs.add(cm);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("schema", SCHEMA);
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
        m.put("knowledgeAssets", knowledgeAssets == null ? List.of() : List.copyOf(knowledgeAssets));
        m.put("computeReceipts", computeReceipts == null ? List.of() : List.copyOf(computeReceipts));
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
