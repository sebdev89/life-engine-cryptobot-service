package io.lifeengine.cryptobot.core.value;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.lifeengine.cryptobot.core.receipts.Digests;
import io.lifeengine.cryptobot.core.value.ValueEvent.AcceptanceProof;
import io.lifeengine.cryptobot.core.value.ValueEvent.AgentIdentity;
import io.lifeengine.cryptobot.core.value.ValueEvent.Contribution;
import io.lifeengine.cryptobot.core.value.ValueEvent.ContributionType;
import io.lifeengine.cryptobot.core.value.ValueEvent.Identity;
import io.lifeengine.cryptobot.core.value.ValueEvent.IdentityKind;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The invariants of the Value Event (KAN-818): identity of the hash, and "value = contribution accepted by someone else". */
class ValueEventTest {

    static final Instant T0 = Instant.parse("2026-09-30T10:00:00Z");
    static final Identity AGENT = new Identity("verticals@1", IdentityKind.AGENT);
    static final Identity HUMAN = new Identity("sebas", IdentityKind.HUMAN);

    static ValueEvent event(Identity contributor, AgentIdentity agent, Identity acceptor, Instant acceptedAt, String evidence) {
        return new ValueEvent("tenant-1", contributor, agent,
                new Contribution(ContributionType.CODE, Digests.sha256(evidence), "github:sebdev89/life-engine-cryptobot-service#pr/48"),
                new AcceptanceProof(acceptor, "human-approval", Digests.sha256("approval"), "jira:KAN-818#comment", acceptedAt),
                T0, "n-1");
    }

    @Test
    @DisplayName("the hash is the content: same event ⇒ same hash; any field changed ⇒ another hash; canonical JSON has no whitespace")
    void hashIsTheContent() {
        ValueEvent a = event(AGENT, null, HUMAN, T0.plusSeconds(60), "diff-1");
        ValueEvent same = event(AGENT, null, HUMAN, T0.plusSeconds(60), "diff-1");
        assertThat(a.hash()).isEqualTo(same.hash()).matches("^sha256:[0-9a-f]{64}$");
        assertThat(a.canonicalJson()).doesNotContain(" ").doesNotContain("\n").startsWith("{\"acceptance\":");
        assertThat(event(AGENT, null, HUMAN, T0.plusSeconds(60), "diff-2").hash()).isNotEqualTo(a.hash());
        assertThat(event(AGENT, null, HUMAN, T0.plusSeconds(61), "diff-1").hash()).isNotEqualTo(a.hash());
        // Domain separation: the id is not the plain hash of its own canonical JSON.
        assertThat(a.hash()).isNotEqualTo(Digests.sha256(a.canonicalJson()));
        assertThat(a.evidenceHashes()).containsExactly(Digests.sha256("diff-1"), Digests.sha256("approval"));
    }

    @Test
    @DisplayName("an agent event: the agent is the contributor and carries its owner; absent optionals are omitted")
    void agentEvent() {
        AgentIdentity agent = new AgentIdentity(AGENT, "sebas", "claude-sonnet-5-5", null);
        ValueEvent e = event(AGENT, agent, HUMAN, T0, "diff-1");
        assertThat(e.canonicalJson()).contains("\"agent\":{\"id\":\"verticals@1\",\"modelRef\":\"claude-sonnet-5-5\",\"ownerId\":\"sebas\"}").doesNotContain("null");
        assertThatThrownBy(() -> event(HUMAN, agent, new Identity("reviewer", IdentityKind.HUMAN), T0, "diff-1"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("agent must be the contributor");
        assertThatThrownBy(() -> new AgentIdentity(HUMAN, "sebas", null, null)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("AGENT");
    }

    @Test
    @DisplayName("nobody accepts their own work, and acceptance cannot precede the contribution")
    void rewardOutcomesNotActivity() {
        assertThatThrownBy(() -> event(AGENT, null, AGENT, T0, "diff-1")).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("own contributor");
        assertThatThrownBy(() -> event(AGENT, null, HUMAN, T0.minusSeconds(1), "diff-1")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("precede");
    }

    @Test
    @DisplayName("evidence must be a sha256 and refs must be plain identifiers (no whitespace, no secrets-looking free text)")
    void evidenceAndRefsAreValidated() {
        assertThatThrownBy(() -> new Contribution(ContributionType.CODE, "abc", "ref")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Contribution(ContributionType.CODE, Digests.sha256("x"), "has spaces in it")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Identity(" ", IdentityKind.HUMAN)).isInstanceOf(IllegalArgumentException.class);
    }
}
