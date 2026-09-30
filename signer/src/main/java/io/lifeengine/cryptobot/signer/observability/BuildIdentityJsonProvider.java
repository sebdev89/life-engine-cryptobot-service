package io.lifeengine.cryptobot.signer.observability;

import ch.qos.logback.classic.spi.ILoggingEvent;
import com.fasterxml.jackson.core.JsonGenerator;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import net.logstash.logback.composite.AbstractJsonProvider;
import org.springframework.lang.Nullable;

/**
 * Escribe {@code version} y {@code commitSha} en cada línea JSON.
 *
 * <p>Es un provider de Logback y no un bean de Spring a propósito: el encoder se configura antes de
 * que exista el contexto de Spring, y las líneas del arranque —las que se leen cuando un contenedor
 * no levanta— también tienen que decir qué build las escribió.
 *
 * <p>Las fuentes son las mismas que usa {@link BuildIdentityResolver} para {@code /actuator/info},
 * con la misma precedencia: la variable {@code GIT_COMMIT} (build-arg de la imagen Docker, que no
 * tiene {@code .git}) gana sobre {@code git.properties} (git-commit-id, en local y CI); la versión
 * sale sólo de {@code META-INF/build-info.properties} (spring-boot:build-info). Lo que no se pudo
 * resolver sale como {@code unknown}, nunca vacío: en Loki "unknown" se filtra, "" se confunde con
 * "no había campo".
 */
public final class BuildIdentityJsonProvider extends AbstractJsonProvider<ILoggingEvent> {

    static final String UNKNOWN = "unknown";
    static final String FIELD_VERSION = "version";
    static final String FIELD_COMMIT = "commitSha";

    private static final int SHORT_COMMIT_LENGTH = 7;

    private final String version;
    private final String commitSha;

    public BuildIdentityJsonProvider() {
        this(
                load("META-INF/build-info.properties"),
                load("git.properties"),
                System.getenv("GIT_COMMIT"));
    }

    BuildIdentityJsonProvider(
            @Nullable Properties buildInfo, @Nullable Properties git, @Nullable String gitCommitOverride) {
        this.version = resolveVersion(buildInfo);
        this.commitSha = resolveCommit(git, gitCommitOverride);
    }

    @Override
    public void writeTo(JsonGenerator generator, ILoggingEvent event) throws IOException {
        generator.writeStringField(FIELD_VERSION, version);
        generator.writeStringField(FIELD_COMMIT, commitSha);
    }

    String version() {
        return version;
    }

    String commitSha() {
        return commitSha;
    }

    static String resolveVersion(@Nullable Properties buildInfo) {
        return buildInfo == null ? UNKNOWN : orUnknown(buildInfo.getProperty("build.version"));
    }

    static String resolveCommit(@Nullable Properties git, @Nullable String override) {
        if (hasText(override)) {
            return shorten(override.trim());
        }
        return git == null ? UNKNOWN : orUnknown(git.getProperty("git.commit.id.abbrev"));
    }

    /** Un sha completo se abrevia a 7, la forma {@code sha-<7>} de los manifiestos de deploy. */
    private static String shorten(String commit) {
        return commit.length() > SHORT_COMMIT_LENGTH ? commit.substring(0, SHORT_COMMIT_LENGTH) : commit;
    }

    private static String orUnknown(@Nullable String value) {
        return hasText(value) ? value.trim() : UNKNOWN;
    }

    private static boolean hasText(@Nullable String value) {
        return value != null && !value.isBlank();
    }

    @Nullable
    private static Properties load(String resource) {
        ClassLoader loader = BuildIdentityJsonProvider.class.getClassLoader();
        try (InputStream in = loader.getResourceAsStream(resource)) {
            if (in == null) {
                return null;
            }
            Properties props = new Properties();
            props.load(in);
            return props;
        } catch (IOException e) {
            return null;
        }
    }
}
