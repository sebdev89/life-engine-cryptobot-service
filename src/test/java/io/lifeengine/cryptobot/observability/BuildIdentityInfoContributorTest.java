package io.lifeengine.cryptobot.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.info.Info;

/**
 * Verifies the {@code /actuator/info} payload shape (LIFE-OPS-02 §2.2) without booting a context, and that
 * absent git/build fields are omitted rather than emitted as nulls.
 */
class BuildIdentityInfoContributorTest {

    @SuppressWarnings("unchecked")
    private static Map<String, Object> contribute(BuildIdentity id) {
        Info.Builder builder = new Info.Builder();
        new BuildIdentityInfoContributor(id).contribute(builder);
        return (Map<String, Object>) (Map<?, ?>) builder.build().getDetails();
    }

    @Test
    @DisplayName("full identity → exact §2.2 nested shape")
    @SuppressWarnings("unchecked")
    void fullShapeMatchesSpec() {
        BuildIdentity id =
                new BuildIdentity(
                        "cryptobot-service",
                        "0.1.0-SNAPSHOT",
                        "uat",
                        "deb347f",
                        "2026-07-13T03:37:26Z",
                        "main",
                        "2026-07-13T03:38:54Z");

        Map<String, Object> d = contribute(id);

        assertThat(d.get("service")).isEqualTo(Map.of("name", "cryptobot-service", "version", "0.1.0-SNAPSHOT"));
        assertThat(d.get("deployment")).isEqualTo(Map.of("environment", "uat"));
        Map<String, Object> git = (Map<String, Object>) d.get("git");
        assertThat(git.get("branch")).isEqualTo("main");
        Map<String, Object> commit = (Map<String, Object>) git.get("commit");
        assertThat(commit).isEqualTo(Map.of("id", "deb347f", "time", "2026-07-13T03:37:26Z"));
        assertThat(d.get("build")).isEqualTo(Map.of("timestamp", "2026-07-13T03:38:54Z"));
    }

    @Test
    @DisplayName("null git/build fields are omitted; service + deployment + git always present")
    @SuppressWarnings("unchecked")
    void omitsNullFields() {
        BuildIdentity id = new BuildIdentity("cryptobot-service", "unknown", "local", null, null, null, null);

        Map<String, Object> d = contribute(id);

        assertThat(d).containsKeys("service", "deployment", "git").doesNotContainKey("build");
        Map<String, Object> git = (Map<String, Object>) d.get("git");
        assertThat(git).doesNotContainKey("branch");
        assertThat((Map<String, Object>) git.get("commit")).isEmpty();
    }
}
