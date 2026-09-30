package io.lifeengine.cryptobot.core.value;

import io.lifeengine.cryptobot.core.intent.JsonCanonicalizer;
import io.lifeengine.cryptobot.core.receipts.Digests;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Proof of Value, V1 (KAN-818): <b>a contribution that somebody accepted</b>. Not "a commit was
 * made" — a {@link Contribution} counts as value only together with an {@link AcceptanceProof}
 * issued by an identity that is not the contributor.
 *
 * <pre>
 *   Identity ──┬── AgentIdentity (an agent acts for an owner Identity)
 *              ├── Contribution  (who, what kind, hash of the evidence)
 *              └── AcceptanceProof (who accepted, how, hash of the acceptance evidence)
 *                       ╰──▶ ValueEvent ──▶ canonical JSON (RFC 8785) ──▶ value-event hash
 * </pre>
 *
 * <p>The hash is {@code SHA-256("life-engine.cryptobot.value-event" ‖ 0x00 ‖ JCS(toMap()))}: the
 * same primitives as the receipts, so a verifier needs nothing new. Everything in the tree is an
 * identifier, a hash, a label or a timestamp — no source code, no prompt, no key.
 */
public record ValueEvent(
        String tenantId,
        Identity contributor,
        AgentIdentity agent,
        Contribution contribution,
        AcceptanceProof acceptance,
        Instant occurredAt,
        String nonce) {

    public static final String SCHEMA = "value-event/1";
    public static final String HASH_DOMAIN = "life-engine.cryptobot.value-event";
    private static final Pattern REF = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._:/#@+\\-]{0,159}$");

    public enum IdentityKind { HUMAN, AGENT, ORGANIZATION }

    /** Who: a stable id (never an email or a wallet key) and what kind of actor it is. */
    public record Identity(String id, IdentityKind kind) {
        public Identity {
            id = requireRef("identity.id", id);
            Objects.requireNonNull(kind, "identity.kind");
        }

        Map<String, Object> toMap() {
            return ordered("id", id, "kind", kind.name());
        }
    }

    /** An agent and the identity that answers for it. {@code modelRef} is the provider's name for the model, when known. */
    public record AgentIdentity(Identity identity, String ownerId, String modelRef, String version) {
        public AgentIdentity {
            Objects.requireNonNull(identity, "agent.identity");
            if (identity.kind() != IdentityKind.AGENT) {
                throw new IllegalArgumentException("agent.identity.kind must be AGENT");
            }
            ownerId = requireRef("agent.ownerId", ownerId);
            modelRef = modelRef == null || modelRef.isBlank() ? null : requireRef("agent.modelRef", modelRef);
            version = version == null || version.isBlank() ? null : requireRef("agent.version", version);
        }

        Map<String, Object> toMap() {
            return ordered("id", identity.id(), "ownerId", ownerId, "modelRef", modelRef, "version", version);
        }
    }

    public enum ContributionType { CODE, ANALYSIS, DATA, REVIEW, OPERATION }

    /**
     * What was contributed. {@code evidenceHash} is the hash of the artefact (a diff, a report, a
     * Life Engine handoff); {@code evidenceRef} says where it lives (PR, run id, issue) so the hash
     * can be correlated with Life Engine's own records.
     */
    public record Contribution(ContributionType type, String evidenceHash, String evidenceRef) {
        public Contribution {
            Objects.requireNonNull(type, "contribution.type");
            evidenceHash = Digests.requireHash("contribution.evidenceHash", evidenceHash);
            evidenceRef = requireRef("contribution.evidenceRef", evidenceRef);
        }

        Map<String, Object> toMap() {
            return ordered("type", type.name(), "evidenceHash", evidenceHash, "evidenceRef", evidenceRef);
        }
    }

    /** Who accepted the contribution, by which method (merge, CI+UAT, human approval…), and the hash of that evidence. */
    public record AcceptanceProof(Identity acceptor, String method, String evidenceHash, String evidenceRef, Instant acceptedAt) {
        public AcceptanceProof {
            Objects.requireNonNull(acceptor, "acceptance.acceptor");
            method = requireRef("acceptance.method", method);
            evidenceHash = Digests.requireHash("acceptance.evidenceHash", evidenceHash);
            evidenceRef = requireRef("acceptance.evidenceRef", evidenceRef);
            Objects.requireNonNull(acceptedAt, "acceptance.acceptedAt");
        }

        Map<String, Object> toMap() {
            return ordered("acceptor", acceptor.toMap(), "method", method, "evidenceHash", evidenceHash, "evidenceRef", evidenceRef,
                    "acceptedAt", acceptedAt.toString());
        }
    }

    public ValueEvent {
        tenantId = requireRef("tenantId", tenantId);
        Objects.requireNonNull(contributor, "contributor");
        Objects.requireNonNull(contribution, "contribution");
        Objects.requireNonNull(acceptance, "acceptance");
        Objects.requireNonNull(occurredAt, "occurredAt");
        nonce = requireRef("nonce", nonce);
        if (agent != null && !agent.identity().equals(contributor)) {
            throw new IllegalArgumentException("the agent must be the contributor");
        }
        if (acceptance.acceptor().id().equals(contributor.id())) {
            throw new IllegalArgumentException("a contribution cannot be accepted by its own contributor");
        }
        if (acceptance.acceptedAt().isBefore(occurredAt)) {
            throw new IllegalArgumentException("acceptance cannot precede the contribution");
        }
    }

    /** The canonical value tree: what gets hashed. Absent optionals are omitted, never null. */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("schema", SCHEMA);
        m.put("tenantId", tenantId);
        m.put("contributor", contributor.toMap());
        if (agent != null) {
            m.put("agent", agent.toMap());
        }
        m.put("contribution", contribution.toMap());
        m.put("acceptance", acceptance.toMap());
        m.put("occurredAt", occurredAt.toString());
        m.put("nonce", nonce);
        return m;
    }

    public byte[] canonicalBytes() {
        return JsonCanonicalizer.canonicalBytes(toMap());
    }

    public String canonicalJson() {
        return new String(canonicalBytes(), StandardCharsets.UTF_8);
    }

    public String hash() {
        return Digests.domainSeparated(HASH_DOMAIN, canonicalBytes());
    }

    /** The hashes this event correlates with outside the service. */
    public List<String> evidenceHashes() {
        List<String> l = new ArrayList<>(2);
        l.add(contribution.evidenceHash());
        l.add(acceptance.evidenceHash());
        return List.copyOf(l);
    }

    private static String requireRef(String field, String value) {
        if (value == null || !REF.matcher(value.trim()).matches()) {
            throw new IllegalArgumentException(field + " is required and must match " + REF.pattern());
        }
        return value.trim();
    }

    private static Map<String, Object> ordered(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            if (kv[i + 1] != null) {
                m.put((String) kv[i], kv[i + 1]);
            }
        }
        return m;
    }
}
