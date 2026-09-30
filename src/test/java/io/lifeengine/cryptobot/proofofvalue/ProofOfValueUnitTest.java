package io.lifeengine.cryptobot.proofofvalue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.lifeengine.cryptobot.application.receipt.AnchorService;
import io.lifeengine.cryptobot.core.receipts.Digests;
import io.lifeengine.cryptobot.observability.CryptobotMetrics;
import io.lifeengine.cryptobot.proofofvalue.ProofOfValueDtos.AcceptanceRequest;
import io.lifeengine.cryptobot.proofofvalue.ProofOfValueDtos.ArtifactRequest;
import io.lifeengine.cryptobot.proofofvalue.ProofOfValueDtos.ContributionRequest;
import io.lifeengine.cryptobot.proofofvalue.ProofOfValueDtos.ValueEventRequest;
import io.lifeengine.cryptobot.proofofvalue.ProofOfValueDtos.ValueEventView;
import io.lifeengine.cryptobot.solana.rpc.SolanaRpcProperties;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** KAN-818: the pure parts — AcceptancePolicy V1, pov/equal-split/v1, the canonical hashes and the response shape. */
class ProofOfValueUnitTest {

    static final String TENANT = "a0000000-0000-4000-8000-000000000001";
    static final String COMMIT = "3fb620dea17f6a7409e15337f5e074fea3097842";
    static final Instant ACCEPTED_AT = Instant.parse("2026-09-30T10:00:00Z");

    // ---- AcceptancePolicy V1 --------------------------------------------------------------------

    @Test
    @DisplayName("AcceptancePolicy V1: las 5 etapas en true → sin violaciones")
    void allFiveStagesTrueIsAccepted() {
        assertThat(AcceptancePolicy.violations(allStages())).isEmpty();
    }

    @Test
    @DisplayName("AcceptancePolicy V1: una etapa false o ausente → una violación por etapa, en orden")
    void anyStageFalseOrMissingIsRejected() {
        Map<String, Boolean> s = allStages();
        s.put("DEPLOYED", false);
        s.remove("ACCEPTED");
        assertThat(AcceptancePolicy.violations(s)).containsExactly("DEPLOYED: false", "ACCEPTED: missing");
        assertThat(AcceptancePolicy.violations(null)).hasSize(5);
        assertThat(AcceptancePolicy.violations(Map.of())).containsExactly("MERGED: missing", "BUILT: missing", "DEPLOYED: missing",
                "RUNNING: missing", "ACCEPTED: missing");
    }

    // ---- DistributionPolicy pov/equal-split/v1 --------------------------------------------------

    @Test
    @DisplayName("equal split: 1 / 3 / 7 contribuciones suman 100, resto al primero, determinista")
    void equalSplitIsDeterministic() {
        assertThat(DistributionPolicy.equalSplit(1)).containsExactly(100);
        assertThat(DistributionPolicy.equalSplit(3)).containsExactly(34, 33, 33);
        assertThat(DistributionPolicy.equalSplit(7)).containsExactly(16, 14, 14, 14, 14, 14, 14);
        for (int n : new int[] {1, 3, 7}) {
            assertThat(Arrays.stream(DistributionPolicy.equalSplit(n)).sum()).isEqualTo(100);
            assertThat(DistributionPolicy.equalSplit(n)).isEqualTo(DistributionPolicy.equalSplit(n));
        }
        assertThatThrownBy(() -> DistributionPolicy.equalSplit(0)).isInstanceOf(IllegalArgumentException.class);
        assertThat(DistributionPolicy.isSupported(null)).isTrue();
        assertThat(DistributionPolicy.isSupported("pov/equal-split/v1")).isTrue();
        assertThat(DistributionPolicy.isSupported("pov/ai-decides/v1")).isFalse();
    }

    // ---- canonical hashes -----------------------------------------------------------------------

    @Test
    @DisplayName("artifactHash = sha256 del JSON canónico (RFC 8785): vector fijo, opcionales omitidos")
    void artifactHashIsSha256OfTheCanonicalJson() {
        ValueEventCanonical.Artifact a = new ValueEventCanonical.Artifact(COMMIT, "https://github.com/x/y/pull/1", null);
        String expectedJson = "{\"commitSha\":\"" + COMMIT + "\",\"prUrl\":\"https://github.com/x/y/pull/1\"}";
        assertThat(ValueEventCanonical.canonical(ValueEventCanonical.artifactTree(a))).isEqualTo(expectedJson);
        assertThat(ValueEventCanonical.hash(ValueEventCanonical.artifactTree(a))).isEqualTo(Digests.sha256(expectedJson));
    }

    @Test
    @DisplayName("acceptanceHash no depende del orden de las etapas y descarta claves desconocidas")
    void acceptanceHashIsStable() {
        Map<String, Boolean> reversed = new LinkedHashMap<>();
        List<String> stages = new java.util.ArrayList<>(AcceptancePolicy.STAGES);
        java.util.Collections.reverse(stages);
        stages.forEach(s -> reversed.put(s, true));
        reversed.put("VIBES", true);
        String a = ValueEventCanonical.hash(ValueEventCanonical.acceptanceTree(new ValueEventCanonical.Acceptance("release-truth", "uat-k8s", allStages(), null, ACCEPTED_AT)));
        String b = ValueEventCanonical.hash(ValueEventCanonical.acceptanceTree(new ValueEventCanonical.Acceptance("release-truth", "uat-k8s", reversed, null, ACCEPTED_AT)));
        assertThat(a).isEqualTo(b);
        String json = ValueEventCanonical.canonical(ValueEventCanonical.acceptanceTree(new ValueEventCanonical.Acceptance("release-truth", "uat-k8s", allStages(), null, ACCEPTED_AT)));
        assertThat(json).isEqualTo("{\"acceptedAt\":\"2026-09-30T10:00:00Z\",\"environment\":\"uat-k8s\",\"source\":\"release-truth\","
                + "\"stages\":{\"ACCEPTED\":true,\"BUILT\":true,\"DEPLOYED\":true,\"MERGED\":true,\"RUNNING\":true}}");
    }

    @Test
    @DisplayName("valueEventHash = sha256(canonical), estable entre llamadas; el orden de contribuciones cambia el reparto y el hash")
    void valueEventHashIsStable() {
        ValueEventService.Draft d1 = ValueEventService.draft(TENANT, request(List.of("sebas", "dev-agent-17", "cryptobot-001")));
        ValueEventService.Draft d2 = ValueEventService.draft(TENANT, request(List.of("sebas", "dev-agent-17", "cryptobot-001")));
        assertThat(d1.valueEventHash()).isEqualTo(d2.valueEventHash()).isEqualTo(Digests.sha256(d1.canonical()));
        assertThat(d1.canonical()).contains("\"artifactHash\":\"" + d1.artifactHash() + "\"").contains("\"acceptanceHash\":\"" + d1.acceptanceHash() + "\"")
                .contains("\"schema\":\"pov/value-event/v1\"").contains("\"totalUnits\":100");
        assertThat(d1.contributions()).extracting(ValueEventCanonical.Contribution::units).containsExactly(34, 33, 33);
        ValueEventService.Draft swapped = ValueEventService.draft(TENANT, request(List.of("dev-agent-17", "sebas", "cryptobot-001")));
        assertThat(swapped.valueEventHash()).isNotEqualTo(d1.valueEventHash());
        assertThat(swapped.artifactHash()).isEqualTo(d1.artifactHash());
        // Another tenant, same content: another event.
        assertThat(ValueEventService.draft("other", request(List.of("sebas", "dev-agent-17", "cryptobot-001"))).valueEventHash())
                .isNotEqualTo(d1.valueEventHash());
    }

    // ---- response shape -------------------------------------------------------------------------

    @Test
    @DisplayName("shape: RECORDED sin anchor, ANCHORED con root/tx/slot/explorer devnet, artifact y acceptance completos")
    void viewShape() {
        ValueEventService.Draft d = ValueEventService.draft(TENANT, request(List.of("sebas", "dev-agent-17")));
        ValueEventRecord rec = new ValueEventRecord(UUID.randomUUID(), TENANT, UUID.fromString(TENANT), "sha256:" + "a".repeat(64), d.valueEventHash(),
                "cryptobot", "KAN-818", "Improve CryptoBot opportunity detection", d.artifactHash(), d.acceptanceHash(), ACCEPTED_AT,
                DistributionPolicy.EQUAL_SPLIT_V1, 100, d.canonical(), ACCEPTED_AT.plusSeconds(5), List.of(
                        new ValueEventRecord.Contribution(0, "sebas", ContributionRole.SPECIFIER, 50, "Sebastián", IdentityKind.HUMAN),
                        new ValueEventRecord.Contribution(1, "dev-agent-17", ContributionRole.IMPLEMENTER, 50, "Dev Agent 17", IdentityKind.AGENT)));
        ValueEventService devnet = service("https://api.devnet.solana.com");

        ValueEventView recorded = devnet.toView(rec, new AnchorService.Inclusion(false, null, null, null, null, null, List.of(), null, null));
        assertThat(recorded.status()).isEqualTo("RECORDED");
        assertThat(recorded.anchor()).isNull();
        assertThat(recorded.anchorStatus()).isNull();
        assertThat(recorded.artifact().commitSha()).isEqualTo(COMMIT);
        assertThat(recorded.artifact().imageDigest()).isNull();
        assertThat(recorded.acceptance().stages()).containsOnlyKeys(AcceptancePolicy.STAGES).doesNotContainValue(false);
        assertThat(recorded.acceptance().acceptedAt()).isEqualTo(ACCEPTED_AT);
        assertThat(recorded.contributions()).extracting(ProofOfValueDtos.ContributionView::units).containsExactly(50, 50);
        assertThat(recorded.knowledgeAssets()).isEmpty();
        assertThat(recorded.computeReceipts()).isEmpty();
        assertThat(recorded.contributions()).extracting(ProofOfValueDtos.ContributionView::derivedFrom).containsOnlyNulls();

        String root = "sha256:" + "b".repeat(64);
        AnchorService.Inclusion finalized = new AnchorService.Inclusion(true, "FINALIZED", "solana-devnet", "5igTx", 77L, root, List.of(), true,
                "https://explorer.solana.com/tx/5igTx?cluster=devnet");
        ValueEventView anchored = devnet.toView(rec, finalized);
        assertThat(anchored.status()).isEqualTo("ANCHORED");
        assertThat(anchored.anchor()).isEqualTo(new ProofOfValueDtos.AnchorRef(root, "5igTx", 77L, "https://explorer.solana.com/tx/5igTx?cluster=devnet"));

        // Local validator in the compose: custom-cluster link with the RPC origin only (never its path or query).
        ValueEventService local = service("http://solana-validator:8899/some/path?api-key=secret");
        assertThat(local.toView(rec, finalized).anchor().explorerUrl())
                .isEqualTo("https://explorer.solana.com/tx/5igTx?cluster=custom&customUrl=http%3A%2F%2Fsolana-validator%3A8899");
    }

    private static ValueEventService service(String devnetUrl) {
        return new ValueEventService(null, null, null, null, null, new SolanaRpcProperties(devnetUrl, null, null), CryptobotMetrics.noop(),
                new ObjectMapper(), java.time.Clock.systemUTC());
    }

    private static Map<String, Boolean> allStages() {
        Map<String, Boolean> s = new LinkedHashMap<>();
        AcceptancePolicy.STAGES.forEach(x -> s.put(x, true));
        return s;
    }

    private static ValueEventRequest request(List<String> contributors) {
        List<ContributionRole> roles = List.of(ContributionRole.SPECIFIER, ContributionRole.IMPLEMENTER, ContributionRole.OPERATOR);
        List<ContributionRequest> cs = new java.util.ArrayList<>();
        for (int i = 0; i < contributors.size(); i++) {
            cs.add(new ContributionRequest(contributors.get(i), roles.get(i % roles.size())));
        }
        return new ValueEventRequest("cryptobot", "KAN-818", "Improve CryptoBot opportunity detection",
                new ArtifactRequest(COMMIT, "https://github.com/sebdev89/life-engine-cryptobot-service/pull/48", null),
                new AcceptanceRequest("release-truth", "uat-k8s", allStages(), null, ACCEPTED_AT), cs, null, null, null);
    }
}
