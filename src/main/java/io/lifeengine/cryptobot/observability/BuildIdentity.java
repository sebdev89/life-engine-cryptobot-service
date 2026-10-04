package io.lifeengine.cryptobot.observability;

/**
 * Verifiable build identity of this service, as exposed on {@code /actuator/info} (LIFE-OPS-02 §2.2) and
 * attached as common tags to every Micrometer metric (T7, replicating the Auth reference
 * implementation an internal ticket).
 *
 * <p>Sources, in order of precedence, are resolved once at startup by {@link BuildIdentityResolver}:
 *
 * <ul>
 *   <li>{@code serviceName} / {@code environment} — configuration ({@code spring.application.name},
 *       {@code lifeengine.deployment.env} ← {@code APP_ENV}). Never hardcoded per environment.
 *   <li>{@code serviceVersion} / {@code buildTimestamp} — {@code META-INF/build-info.properties}
 *       (spring-boot:build-info), baked at package time and therefore immutable for a given artifact.
 *   <li>{@code gitCommit} / {@code gitCommitTime} / {@code gitBranch} — {@code git.properties}
 *       (git-commit-id) when a {@code .git} directory is present (local + CI), or the injected
 *       build-args when it is not (a future Docker image — CryptoBot is not yet containerised).
 * </ul>
 *
 * <p>Nullable git/build fields mean "not available in this build context"; the contributor omits them
 * rather than emitting nulls.
 */
public record BuildIdentity(
        String serviceName,
        String serviceVersion,
        String environment,
        String gitCommit,
        String gitCommitTime,
        String gitBranch,
        String buildTimestamp) {}
