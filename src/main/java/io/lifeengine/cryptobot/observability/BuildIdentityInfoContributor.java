package io.lifeengine.cryptobot.observability;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.actuate.info.Info;
import org.springframework.boot.actuate.info.InfoContributor;

/**
 * Emits the build identity on {@code /actuator/info} in the exact shape of LIFE-OPS-02 §2.2:
 *
 * <pre>
 * {
 *   "service":    { "name": ..., "version": ... },
 *   "deployment": { "environment": ... },
 *   "git":        { "commit": { "id": ..., "time": ... }, "branch": ... },
 *   "build":      { "timestamp": ... }
 * }
 * </pre>
 *
 * <p>Nullable git/build fields (absent in a given build context) are omitted rather than emitted as nulls,
 * so the payload never advertises data it does not have.
 */
class BuildIdentityInfoContributor implements InfoContributor {

    private final BuildIdentity identity;

    BuildIdentityInfoContributor(BuildIdentity identity) {
        this.identity = identity;
    }

    @Override
    public void contribute(Info.Builder builder) {
        builder.withDetail("service", Map.of("name", identity.serviceName(), "version", identity.serviceVersion()));
        builder.withDetail("deployment", Map.of("environment", identity.environment()));

        Map<String, Object> commit = new LinkedHashMap<>();
        if (identity.gitCommit() != null) {
            commit.put("id", identity.gitCommit());
        }
        if (identity.gitCommitTime() != null) {
            commit.put("time", identity.gitCommitTime());
        }
        Map<String, Object> git = new LinkedHashMap<>();
        git.put("commit", commit);
        if (identity.gitBranch() != null) {
            git.put("branch", identity.gitBranch());
        }
        builder.withDetail("git", git);

        if (identity.buildTimestamp() != null) {
            builder.withDetail("build", Map.of("timestamp", identity.buildTimestamp()));
        }
    }
}
