package io.lifeengine.cryptobot.observability;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.info.GitProperties;
import org.springframework.lang.Nullable;

/**
 * Pure resolution of {@link BuildIdentity} from configuration, {@link GitProperties}, {@link BuildProperties}
 * and injected build-arg overrides. No Spring context required — kept side-effect free so the precedence
 * rules can be unit-tested directly (see {@code BuildIdentityResolverTest}).
 *
 * <p>Precedence for the git fields: an explicit override (the Docker build-arg path, where no {@code .git}
 * exists) wins; otherwise {@link GitProperties} (the local/CI path). {@code serviceVersion} and
 * {@code buildTimestamp} come only from {@link BuildProperties} — they are properties of the artifact, not of
 * the deploy.
 */
final class BuildIdentityResolver {

    static final String UNKNOWN = "unknown";

    /** Short commit length matching the {@code sha-<7>} convention used by the deploy manifests. */
    private static final int SHORT_COMMIT_LENGTH = 7;

    private BuildIdentityResolver() {}

    static BuildIdentity resolve(
            String serviceName,
            String environment,
            @Nullable String gitCommitOverride,
            @Nullable String gitBranchOverride,
            @Nullable String gitCommitTimeOverride,
            @Nullable GitProperties git,
            @Nullable BuildProperties build) {

        String version = (build != null && hasText(build.getVersion())) ? build.getVersion() : UNKNOWN;
        String buildTimestamp = (build != null && build.getTime() != null) ? iso(build.getTime()) : null;

        String commit = firstNonBlank(shorten(gitCommitOverride), git != null ? git.getShortCommitId() : null);
        String branch = firstNonBlank(gitBranchOverride, git != null ? git.getBranch() : null);
        String commitTime =
                firstNonBlank(
                        gitCommitTimeOverride,
                        (git != null && git.getCommitTime() != null) ? iso(git.getCommitTime()) : null);

        return new BuildIdentity(
                defaulted(serviceName), version, defaulted(environment), commit, commitTime, branch, buildTimestamp);
    }

    private static String iso(Instant instant) {
        return DateTimeFormatter.ISO_INSTANT.format(instant);
    }

    /** Abbreviates a full override sha to the 7-char short form; leaves already-short ids untouched. */
    @Nullable
    private static String shorten(@Nullable String commit) {
        if (!hasText(commit)) {
            return null;
        }
        String trimmed = commit.trim();
        return trimmed.length() > SHORT_COMMIT_LENGTH ? trimmed.substring(0, SHORT_COMMIT_LENGTH) : trimmed;
    }

    @Nullable
    private static String firstNonBlank(@Nullable String a, @Nullable String b) {
        if (hasText(a)) {
            return a.trim();
        }
        return hasText(b) ? b.trim() : null;
    }

    private static String defaulted(@Nullable String value) {
        return hasText(value) ? value.trim() : UNKNOWN;
    }

    private static boolean hasText(@Nullable String s) {
        return s != null && !s.isBlank();
    }
}
