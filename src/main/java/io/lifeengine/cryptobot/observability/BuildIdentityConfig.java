package io.lifeengine.cryptobot.observability;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.info.InfoContributor;
import org.springframework.boot.actuate.autoconfigure.metrics.MeterRegistryCustomizer;
import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.info.GitProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the service's build identity (KAN-199 / T7, replicating Auth KAN-194) into the two surfaces
 * observability consumes it from:
 *
 * <ul>
 *   <li><b>{@code /actuator/info}</b> — via {@link InfoContributor}, in the exact shape of LIFE-OPS-02 §2.2.
 *       The default {@code env}/{@code git}/{@code build} info contributors are disabled in
 *       {@code application.yml} so this contributor alone owns the payload.
 *   <li><b>Every Micrometer metric</b> — {@code environment} / {@code service} / {@code version} /
 *       {@code commit} common tags, so {@code /actuator/prometheus} is filterable by build identity.
 * </ul>
 *
 * <p>{@code /actuator/info} stays blocked at nginx (never public); it is read from inside the Docker network.
 */
@Configuration
class BuildIdentityConfig {

    /**
     * Resolved once at startup. {@code identity.git.*} are empty by default and only set (as build-args →
     * env) when the image is built without a {@code .git} directory — for a future CryptoBot Dockerfile.
     */
    @Bean
    BuildIdentity buildIdentity(
            @Value("${spring.application.name:cryptobot-service}") String serviceName,
            @Value("${lifeengine.deployment.env:local}") String environment,
            @Value("${identity.git.commit:}") String gitCommitOverride,
            @Value("${identity.git.branch:}") String gitBranchOverride,
            @Value("${identity.git.commit-time:}") String gitCommitTimeOverride,
            ObjectProvider<GitProperties> gitProperties,
            ObjectProvider<BuildProperties> buildProperties) {
        return BuildIdentityResolver.resolve(
                serviceName,
                environment,
                gitCommitOverride,
                gitBranchOverride,
                gitCommitTimeOverride,
                gitProperties.getIfAvailable(),
                buildProperties.getIfAvailable());
    }

    @Bean
    InfoContributor buildIdentityInfoContributor(BuildIdentity identity) {
        return new BuildIdentityInfoContributor(identity);
    }

    /**
     * Common tags on every meter. Micrometer rejects null tag values, so an absent commit (a build with
     * neither {@code .git} nor an override) degrades to {@code "unknown"} rather than failing startup.
     */
    @Bean
    MeterRegistryCustomizer<MeterRegistry> buildIdentityCommonTags(BuildIdentity identity) {
        return registry ->
                registry.config()
                        .commonTags(
                                "environment", nonNull(identity.environment()),
                                "service", nonNull(identity.serviceName()),
                                "version", nonNull(identity.serviceVersion()),
                                "commit", nonNull(identity.gitCommit()));
    }

    private static String nonNull(String value) {
        return value != null ? value : BuildIdentityResolver.UNKNOWN;
    }
}
