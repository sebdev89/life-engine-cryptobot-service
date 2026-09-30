package io.lifeengine.cryptobot.proofofvalue;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import io.lifeengine.cryptobot.application.receipt.AnchorService;
import io.lifeengine.cryptobot.application.receipt.ReceiptService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Request and response shapes of the Proof of Value API (KAN-818; V2–V4 + V6: KAN-819). Requests are validated with {@code @Valid}; a failure is a 400. */
public final class ProofOfValueDtos {

    public static final String ID = "^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$";
    public static final String REF = "^[A-Za-z0-9][A-Za-z0-9._:/#-]{0,63}$";
    public static final String WALLET = "^[1-9A-HJ-NP-Za-km-z]{32,44}$";
    public static final String COMMIT = "^[0-9a-f]{7,64}$";
    public static final String DIGEST = "^sha256:[0-9a-f]{64}$";
    public static final String URL = "^https?://[^\\s]{1,290}$";
    /** A knowledge asset id: like {@link #ID}, plus {@code @} for a version suffix ({@code strategy-knowledge@3}). */
    public static final String ASSET_ID = "^[A-Za-z0-9][A-Za-z0-9._@-]{0,79}$";

    private ProofOfValueDtos() {}

    // ---- identities ---------------------------------------------------------------------------

    public record IdentityRequest(
            @NotBlank @Pattern(regexp = ID) String id,
            @NotNull IdentityKind kind,
            @NotBlank @Size(max = 120) String displayName,
            @Pattern(regexp = WALLET) String wallet,
            @Pattern(regexp = ID) String ownerId,
            @Pattern(regexp = ID) String operatorId) {}

    public record IdentityView(String id, IdentityKind kind, String displayName, String wallet, String ownerId, String operatorId, Instant createdAt) {
        static IdentityView of(PovIdentity i) {
            return new IdentityView(i.id(), i.kind(), i.displayName(), i.wallet(), i.ownerId(), i.operatorId(), i.createdAt());
        }
    }

    /**
     * Reputation V2 (KAN-819): explicit counts, no formula. {@code acceptedOutcomes} = distinct accepted ValueEvents the
     * identity contributed to; {@code totalUnits} = the Contribution Units it received in them; first/last = their
     * {@code acceptedAt}. {@code null} timestamps when it has none yet.
     */
    public record ReputationView(long acceptedOutcomes, long totalUnits, Instant firstAcceptedAt, Instant lastAcceptedAt) {
        public static final ReputationView NONE = new ReputationView(0, 0, null, null);
    }

    /** {@code GET /identities}: the identity plus its reputation. */
    public record IdentitySummaryView(String id, IdentityKind kind, String displayName, String wallet, String ownerId, String operatorId,
            Instant createdAt, ReputationView reputation) {
        static IdentitySummaryView of(PovIdentity i, ReputationView r) {
            return new IdentitySummaryView(i.id(), i.kind(), i.displayName(), i.wallet(), i.ownerId(), i.operatorId(), i.createdAt(), r);
        }
    }

    /** One contribution of the identity, newest first. {@code anchorStatus} is the event's RECORDED/ANCHORED. */
    public record HistoryEntryView(UUID valueEventId, String title, ContributionRole role, int units, Instant acceptedAt, String anchorStatus) {}

    /** {@code GET /identities/{id}}: the identity, its reputation and its history. */
    public record IdentityProfileView(String id, IdentityKind kind, String displayName, String wallet, String ownerId, String operatorId,
            Instant createdAt, ReputationView reputation, List<HistoryEntryView> history, RewardsView rewards) {}

    /**
     * KAN-822 (V5): what the identity was paid by immediate rewards. {@code confirmedLamports} = devnet lamports of its CONFIRMED
     * payouts; {@code payouts} = how many payouts it has in any state (UNFUNDED and FAILED included).
     */
    public record RewardsView(long confirmedLamports, long payouts) {
        public static final RewardsView NONE = new RewardsView(0, 0);
    }

    // ---- knowledge assets (V3) ----------------------------------------------------------------

    public record KnowledgeAssetRequest(
            @NotBlank @Pattern(regexp = ASSET_ID) String id,
            @NotNull @Min(1) @Max(1_000_000) Integer version,
            @NotNull KnowledgeAssetKind kind,
            @NotBlank @Size(max = 200) String title,
            @NotBlank @Pattern(regexp = ID) String creatorId,
            @NotBlank @Pattern(regexp = DIGEST) String contentHash,
            @Size(max = 16) List<@NotBlank @Pattern(regexp = ASSET_ID) String> parentIds) {}

    public record KnowledgeAssetView(String id, int version, KnowledgeAssetKind kind, String title, String creatorId, String creatorDisplayName,
            String contentHash, List<String> parentIds, Instant createdAt, List<UUID> usedIn) {
        static KnowledgeAssetView of(PovKnowledgeAsset a, List<UUID> usedIn) {
            return new KnowledgeAssetView(a.id(), a.version(), a.kind(), a.title(), a.creatorId(), a.creatorDisplayName(), a.contentHash(),
                    a.parentIds(), a.createdAt(), usedIn == null ? List.of() : usedIn);
        }
    }

    // ---- units ledger (V6) --------------------------------------------------------------------

    /** {@code kind}: the identity kind, the asset kind, or {@code null} for a project / the unattributed row. */
    public record LedgerRowView(String key, String displayName, String kind, long totalUnits, long acceptedOutcomes) {}

    /** The rows always add up to {@code totalUnits}, which is the sum of {@code totalUnits} of every recorded event. */
    public record LedgerView(String groupBy, List<LedgerRowView> rows, long totalUnits) {}

    // ---- value events -------------------------------------------------------------------------

    public record ArtifactRequest(
            @NotBlank @Pattern(regexp = COMMIT) String commitSha,
            @Pattern(regexp = URL) String prUrl,
            @Pattern(regexp = DIGEST) String imageDigest) {}

    public record AcceptanceRequest(
            @NotBlank @Size(max = 64) String source,
            @NotBlank @Size(max = 32) String environment,
            @NotNull Map<String, Boolean> stages,
            @Size(max = 300) String evidenceRef,
            @NotNull Instant acceptedAt) {}

    public record ContributionRequest(@NotBlank @Pattern(regexp = ID) String identityId, @NotNull ContributionRole role) {}

    public record ValueEventRequest(
            @NotBlank @Pattern(regexp = REF) String projectId,
            @NotBlank @Pattern(regexp = REF) String taskId,
            @NotBlank @Size(max = 200) String title,
            @NotNull @Valid ArtifactRequest artifact,
            @NotNull @Valid AcceptanceRequest acceptance,
            @NotEmpty @Size(max = 32) List<@NotNull @Valid ContributionRequest> contributions,
            @Size(max = 50) List<@NotBlank @Pattern(regexp = ASSET_ID) String> knowledgeAssets,
            @Size(max = 20) List<@NotNull @Valid ComputeReceiptRequest> computeReceipts,
            @Size(max = 64) String distributionPolicy) {}

    /**
     * V4 (KAN-821): what the outcome cost to compute. {@code gpuSeconds} has at most 3 decimals (it is committed as
     * integer milliseconds). Cost is recorded next to the event; it never enters the distribution of units.
     */
    public record ComputeReceiptRequest(
            @NotBlank @Pattern(regexp = ID) String providerId,
            @NotBlank @Size(max = 120) String node,
            @NotBlank @Size(max = 120) String model,
            @NotNull @PositiveOrZero Long inputTokens,
            @NotNull @PositiveOrZero Long outputTokens,
            @NotNull @DecimalMin("0") @Digits(integer = 9, fraction = 3) BigDecimal gpuSeconds,
            @NotNull @PositiveOrZero Long estimatedCostMicroUsd) {}

    /** The on-chain side: the Merkle root in the memo, the memo transaction, its slot and the explorer link. */
    public record AnchorRef(String root, String txSignature, Long slot, String explorerUrl) {}

    /**
     * {@code derivedFrom}: {@code null} for a contribution in the request; for a KNOWLEDGE_PROVIDER contribution the
     * service added for an asset's creator (KAN-819), the ids of those assets — provenance, not an economic decision.
     */
    public record ContributionView(String identityId, String displayName, IdentityKind kind, ContributionRole role, int units, List<String> derivedFrom) {}

    /** V3: an asset as committed in the event's canonical JSON. */
    public record EventKnowledgeAssetView(String id, Integer version, String kind, String title, String creatorId, String contentHash) {}

    /** V4: a compute receipt of the event. {@code gpuSeconds} = the committed milliseconds / 1000. */
    public record ComputeReceiptView(UUID id, String providerId, String providerDisplayName, String node, String model, long inputTokens,
            long outputTokens, double gpuSeconds, long estimatedCostMicroUsd, String providerWallet) {}

    public record ArtifactView(String commitSha, String prUrl, String imageDigest) {}

    public record AcceptanceView(String source, String environment, Map<String, Boolean> stages, String evidenceRef, Instant acceptedAt) {}

    /**
     * {@code status} is RECORDED until the VALUE_EVENT receipt's batch is FINALIZED on devnet, then
     * ANCHORED. {@code anchorStatus} is the batch state (PENDING/SUBMITTED/FAILED/FINALIZED) or
     * {@code null} before the receipt enters a batch.
     */
    public record ValueEventView(
            UUID id,
            String receiptHash,
            String valueEventHash,
            String artifactHash,
            String acceptanceHash,
            String status,
            String anchorStatus,
            AnchorRef anchor,
            String distributionPolicy,
            int totalUnits,
            List<ContributionView> contributions,
            ArtifactView artifact,
            AcceptanceView acceptance,
            List<EventKnowledgeAssetView> knowledgeAssets,
            List<ComputeReceiptView> computeReceipts,
            String projectId,
            String taskId,
            String title,
            Instant acceptedAt,
            Instant createdAt,
            DistributionSummaryView distribution) {}

    /** KAN-822 (V5): the event's immediate reward at a glance; {@code null} on the event until one is distributed. */
    public record DistributionSummaryView(String status, long poolLamports, long confirmedLamports) {}

    /** KAN-822 (V5): one contributor's payout. {@code wallet} null ⇒ UNFUNDED; {@code error} only when FAILED (or broadcast uncertain). */
    public record PayoutView(String identityId, String displayName, String wallet, long lamports, String status, String txSignature,
            String explorerUrl, String error) {}

    /**
     * KAN-822 (V5): {@code POST /value-events/{id}/distribute} and {@code GET /value-events/{id}/distribution}. {@code status}
     * IN_PROGRESS | PARTIAL | COMPLETE | FAILED, derived from the payouts. {@code receiptHash}: the VALUE_DISTRIBUTION receipt;
     * {@code anchor}: its Merkle batch on devnet once finalized, else {@code null}. Amounts in lamports of devnet SOL — devnet SOL
     * stands in for stablecoin settlement in this demo.
     */
    public record DistributionView(UUID id, UUID valueEventId, long poolLamports, String policy, String status, String receiptHash, AnchorRef anchor,
            long confirmedLamports, List<PayoutView> payouts, Instant createdAt) {}

    /**
     * {@code GET /value-events/{id}/proof}: what {@code POST /receipts/{hash}/verify} returns for the
     * VALUE_EVENT receipt (its checks unwrapped, and {@code anchor} = the Merkle inclusion), plus the
     * correlation — {@code receiptHash} ∈ tree → {@code root} in the memo of {@code txSignature} — and
     * {@code valueEventHashValid}: the stored canonical JSON still hashes to the receipt's output hash.
     * {@code verified} is the one bit a script checks: all of it holds and the batch is finalized.
     */
    public record ProofView(
            @JsonUnwrapped ReceiptService.Verification receipt,
            AnchorService.Inclusion anchor,
            UUID valueEventId,
            String valueEventHash,
            boolean valueEventHashValid,
            String root,
            String txSignature,
            Long slot,
            String explorerUrl,
            boolean verified) {}
}
