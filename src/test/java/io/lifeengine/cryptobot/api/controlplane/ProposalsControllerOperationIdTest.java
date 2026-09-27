package io.lifeengine.cryptobot.api.controlplane;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.lifeengine.cryptobot.core.intent.IntentHash;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** KAN-435: the intent hash is the operationId of KAN-403 — derived, deterministic, never invented. */
class ProposalsControllerOperationIdTest {

    private static final String HASH = "sha256:877dcaf96566ba02b058d41c01af02ff69d8d4c60dc375a610f3f9f15aa89081";

    @Test
    void uuidKeysStillWork() {
        UUID u = UUID.randomUUID();
        assertThat(ProposalsController.operationIdOf(u.toString())).isEqualTo(u);
    }

    @Test
    void intentHashDerivesTheOperationId_caseInsensitivePrefix() {
        UUID fromHash = ProposalsController.operationIdOf(HASH);
        assertThat(fromHash).isEqualTo(IntentHash.parse(HASH).toOperationId());
        assertThat(fromHash).isEqualTo(ProposalsController.operationIdOf(HASH.toUpperCase()));
        assertThat(fromHash.toString()).isEqualTo("877dcaf9-6566-ba02-b058-d41c01af02ff");
    }

    /** KAN-500: the hash itself is what gets persisted (action_proposal.intent_hash), canonical lower-case; a UUID key has none. */
    @Test
    void intentHashOfIsCanonicalOrNull() {
        assertThat(ProposalsController.intentHashOf(HASH)).isEqualTo(HASH);
        assertThat(ProposalsController.intentHashOf(HASH.toUpperCase())).isEqualTo(HASH);
        assertThat(ProposalsController.intentHashOf(UUID.randomUUID().toString())).isNull();
        assertThatThrownBy(() -> ProposalsController.intentHashOf("sha256:zz")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void malformedKeysAreIllegalArguments() {
        assertThatThrownBy(() -> ProposalsController.operationIdOf("sha256:zz")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ProposalsController.operationIdOf("sha256:")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ProposalsController.operationIdOf("not-a-uuid")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ProposalsController.operationIdOf("877dcaf96566ba02b058d41c01af02ff69d8d4c60dc375a610f3f9f15aa89081"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
