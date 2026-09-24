package io.lifeengine.cryptobot.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Properties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BuildIdentityJsonProviderTest {

    @Test
    @DisplayName("la imagen Docker no tiene .git: GIT_COMMIT (build-arg) gana y se abrevia a 7")
    void overrideWinsAndIsShortened() {
        Properties git = props("git.commit.id.abbrev", "abc1234");
        var provider = new BuildIdentityJsonProvider(props("build.version", "1.4.0"), git, "0123456789abcdef");

        assertThat(provider.commitSha()).isEqualTo("0123456");
        assertThat(provider.version()).isEqualTo("1.4.0");
    }

    @Test
    @DisplayName("local y CI: sin override, el commit sale de git.properties")
    void gitPropertiesWhenNoOverride() {
        var provider = new BuildIdentityJsonProvider(props("build.version", "1.4.0"), props("git.commit.id.abbrev", "abc1234"), " ");

        assertThat(provider.commitSha()).isEqualTo("abc1234");
    }

    @Test
    @DisplayName("lo que no se pudo resolver es 'unknown', nunca vacío")
    void unknownNeverBlank() {
        var provider = new BuildIdentityJsonProvider(null, null, null);

        assertThat(provider.version()).isEqualTo(BuildIdentityJsonProvider.UNKNOWN);
        assertThat(provider.commitSha()).isEqualTo(BuildIdentityJsonProvider.UNKNOWN);
    }

    @Test
    @DisplayName("el classpath de este build trae build-info y git.properties de verdad")
    void classpathResolves() {
        var provider = new BuildIdentityJsonProvider();

        assertThat(provider.version()).isNotEqualTo(BuildIdentityJsonProvider.UNKNOWN);
        assertThat(provider.commitSha()).isNotEqualTo(BuildIdentityJsonProvider.UNKNOWN).hasSizeBetween(7, 40);
    }

    private static Properties props(String key, String value) {
        Properties p = new Properties();
        p.setProperty(key, value);
        return p;
    }
}
