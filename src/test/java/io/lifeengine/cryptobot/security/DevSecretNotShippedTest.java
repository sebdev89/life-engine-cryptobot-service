package io.lifeengine.cryptobot.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * KAN-350 — el binario no trae ningún secreto HS256 por default, en ningún perfil.
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
