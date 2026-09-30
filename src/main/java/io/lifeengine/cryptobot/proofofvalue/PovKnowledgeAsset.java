package io.lifeengine.cryptobot.proofofvalue;

import java.time.Instant;
import java.util.List;

/**
 * A knowledge asset as stored ({@code pov_knowledge_asset}, V13): a versioned, content-addressed piece of
 * knowledge (a ruleset, a prompt, a strategy…) and the identity that created it. {@code parentIds} name the
 * assets it derives from (same tenant). {@code creatorDisplayName} comes from {@code pov_identity} on read.
 */
public record PovKnowledgeAsset(
        String tenantId,
        String id,
        int version,
        KnowledgeAssetKind kind,
        String title,
        String creatorId,
        String contentHash,
        List<String> parentIds,
        Instant createdAt,
        String creatorDisplayName) {

    public PovKnowledgeAsset {
        parentIds = parentIds == null ? List.of() : List.copyOf(parentIds);
    }

    public PovKnowledgeAsset withCreatorDisplayName(String name) {
        return new PovKnowledgeAsset(tenantId, id, version, kind, title, creatorId, contentHash, parentIds, createdAt, name);
    }
}
