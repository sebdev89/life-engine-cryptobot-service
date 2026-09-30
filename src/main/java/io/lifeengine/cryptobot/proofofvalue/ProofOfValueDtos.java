package io.lifeengine.cryptobot.proofofvalue;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import io.lifeengine.cryptobot.application.receipt.AnchorService;
import io.lifeengine.cryptobot.application.receipt.ReceiptService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Request and response shapes of the Proof of Value API (KAN-818). Requests are validated with {@code @Valid}; a failure is a 400. */
public final class ProofOfValueDtos {

    public static final String ID = "^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$";
    public static final String REF = "^[A-Za-z0-9][A-Za-z0-9._:/#-]{0,63}$";
    public static final String WALLET = "^[1-9A-HJ-NP-Za-km-z]{32,44}$";
    public static final String COMMIT = "^[0-9a-f]{7,64}$";
    public static final String DIGEST = "^sha256:[0-9a-f]{64}$";
    public static final String URL = "^https?://[^\\s]{1,290}$";

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
            @Size(max = 50) List<@NotBlank @Size(max = 200) String> knowledgeAssets,
            @Size(max = 50) List<@NotBlank @Size(max = 200) String> computeReceipts,
            @Size(max = 64) String distributionPolicy) {}

    /** The on-chain side: the Merkle root in the memo, the memo transaction, its slot and the explorer link. */
    public record AnchorRef(String root, String txSignature, Long slot, String explorerUrl) {}

    public record ContributionView(String identityId, String displayName, IdentityKind kind, ContributionRole role, int units) {}

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
            List<String> knowledgeAssets,
            List<String> computeReceipts,
            String projectId,
            String taskId,
            String title,
            Instant acceptedAt,
            Instant createdAt) {}

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
