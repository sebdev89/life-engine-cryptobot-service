package io.lifeengine.cryptobot.signer.observability;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.autoconfigure.metrics.MeterRegistryCustomizer;
import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.info.GitProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Common tags de la identidad del build en cada meter (KAN-573): {@code environment} / {@code service} /
 * {@code version} / {@code commit}, los mismos que cryptobot-service y los otros seis servicios, para que
 * {@code /actuator/prometheus} del signerNAME sea filtrable por build. El commit prefiere el build-arg
 * {@code GIT_COMMIT} (la imagen no tiene .git, KAN-579) y cae a {@code git.properties}; lo que no se
 * resuelve es {@code unknown}, nunca vacío (Micrometer rechaza tags nulos).
 */
@Configuration
class BuildIdentityCommonTags {

    static final String UNKNOWN = "unknown";

    @Bean
    MeterRegistryCustomizer<MeterRegistry> buildIdentityMeterTags(
            @Value("${spring.application.name:cryptobot-signer}") String serviceName,
            @Value("${lifeengine.deployment.env:local}") String environment,
            @Value("${identity.git.commit:}") String gitCommitOverride,
            ObjectProvider<GitProperties> git,
            ObjectProvider<BuildProperties> build) {
        String version = build.getIfAvailable() != null && hasText(build.getIfAvailable().getVersion()) ? build.getIfAvailable().getVersion() : UNKNOWN;
        String commit = hasText(gitCommitOverride) ? shorten(gitCommitOverride.trim())
                : git.getIfAvailable() != null && hasText(git.getIfAvailable().getShortCommitId()) ? git.getIfAvailable().getShortCommitId() : UNKNOWN;
        return registry -> registry.config().commonTags(
                "environment", hasText(environment) ? environment : UNKNOWN,
                "service", hasText(serviceName) ? serviceName : UNKNOWN,
                "version", version,
                "commit", commit);
    }

    private static String shorten(String commit) {
        return commit.length() > 7 ? commit.substring(0, 7) : commit;
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }
}
