package io.lifeengine.cryptobot.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * el binario no trae ningún secreto HS256 por default, en ningún perfil.
 *
 * <p>{@code application-local.yml} traía {@code secret: ${JWT_SECRET:<default de 51 bytes>}}. Ese
 * default supera los 32 bytes que {@link CryptobotJwtService} exige para aceptar una clave HMAC,
 * así que cualquier imagen que activara el perfil {@code local} sin exportar {@code JWT_SECRET}
 * verificaba HS256 con una clave que estuvo pública en el repositorio de Runtime y se trata como
 * comprometida.
 *
 * <p>El guard del código ya existía (ver {@code CryptobotJwtServiceTest}): con secreto vacío y sin
 * JWKS el servicio no se construye. Lo que faltaba era el guard del <em>archivo</em>, que es el
 * único que falla si alguien vuelve a escribir un default.
 *
 * <p>an internal ticket lo extiende a TODA credencial del {@code application.yml} común (S2S, DB, tokens,
 * api keys): ninguna variable con nombre de credencial tiene default. {@code application-local.yml}
 * sólo aplica con el perfil {@code local} y conserva el par de desarrollo de Postgres; su secreto
 * S2S tampoco tiene default.
 */
class DevSecretNotShippedTest {

    @ParameterizedTest(name = "{0}: el secreto NO tiene fallback (JWT_SECRET con default vacío)")
    @ValueSource(strings = {"/application.yml", "/application-local.yml"})
    void configShipsNoUsableSecretDefault(String resource) throws IOException {
        String yaml = read(resource);

        var secreto =
                yaml.lines()
                        .map(String::trim)
                        .filter(l -> l.startsWith("secret:"))
                        .filter(l -> l.contains("JWT_SECRET"))
                        .findFirst()
                        .orElseThrow(
                                () ->
                                        new AssertionError(
                                                "no se encontró la propiedad del secreto en "
                                                        + resource
                                                        + "; si se movió, este guard hay que"
                                                        + " moverlo con ella"));

        assertThat(secreto)
                .as(
                        "un default de más de 32 bytes en %s deja la verificación HS256 activa con"
                                + " una clave que está en el repositorio. El secreto local va por"
                                + " variable de entorno (.env.template).",
                        resource)
                .isEqualTo("secret: ${JWT_SECRET:}");
    }

    /**
     * Nombre de variable que designa una credencial. Amplio a propósito: un falso positivo cuesta
     * agregar una excepción explícita; un falso negativo es un secreto en el jar.
     */
    private static final Pattern CREDENTIAL_VAR =
            Pattern.compile(".*(SECRET|PASSWORD|PASS|TOKEN|API_KEY|_KEY|LOGIN|DB_USER|USERNAME).*");

    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([A-Z0-9_]+):([^}]*)\\}");

    @Test
    @DisplayName("application.yml (común) no trae un valor default para ninguna credencial")
    void sharedConfigHasNoCredentialDefault() throws IOException {
        String yaml = read("/application.yml");
        List<String> violations = new ArrayList<>();
        Matcher m = PLACEHOLDER.matcher(yaml);
        while (m.find()) {
            if (CREDENTIAL_VAR.matcher(m.group(1)).matches() && !m.group(2).isBlank()) {
                violations.add("${" + m.group(1) + ":" + m.group(2) + "}");
            }
        }
        assertThat(violations)
                .as(
                        "un default de credencial en el archivo común viaja en el jar a todos los"
                            + " ambientes. Los valores locales van por .env.local (.env.template) o"
                            + " application-local.yml; en los ambientes, por SOPS/compose.")
                .isEmpty();
    }

    @ParameterizedTest(name = "{0}: la credencial S2S existe y sin default")
    @ValueSource(strings = {"/application.yml"})
    void s2sCredentialHasNoDefault(String resource) throws IOException {
        String yaml = read(resource);
        assertThat(yaml)
                .contains("client-id: ${CRYPTOBOT_S2S_CLIENT_ID:}")
                .contains("client-secret: ${CRYPTOBOT_S2S_CLIENT_SECRET:}")
                .doesNotContain("service-token:");
    }

    @Test
    @DisplayName("sin JWT_SECRET y sin JWKS, el servicio NO arranca (fail-fast con mensaje claro)")
    void refusesToStartWithNeitherSecretNorJwks() {
        // Es lo que pasa en cualquier ambiente que no exporte ninguna de las dos variables desde
        // que el YAML dejó de tener default: falla cerrado, en vez de arrancar sin poder decidir.
        JwksPublicKeyProvider sinJwks = mock(JwksPublicKeyProvider.class);
        when(sinJwks.isConfigured()).thenReturn(false);

        assertThatThrownBy(
                        () ->
                                new CryptobotJwtService(
                                        new CryptobotJwtProperties(""),
                                        new CryptobotRuntimeSecurityProperties(true),
                                        sinJwks))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(
                        "lifeengine.security.jwt.secret must be at least 32 UTF-8 bytes");
    }

    private String read(String resource) throws IOException {
        try (InputStream in = getClass().getResourceAsStream(resource)) {
            assertThat(in).as("recurso %s en el classpath de main", resource).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
