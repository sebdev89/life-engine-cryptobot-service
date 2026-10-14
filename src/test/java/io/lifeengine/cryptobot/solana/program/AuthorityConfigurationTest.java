package io.lifeengine.cryptobot.solana.program;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/**
 * Lo que se prueba acá no es que el cliente funcione —eso es {@link IntentAuthorityProgramTest}—
 * sino <b>cuándo</b> se descubre una configuración que no puede funcionar (KAN-752).
 *
 * <p>El modo de falla que este test existe para impedir: una propuesta que pasa riesgo, política,
 * simulación y <b>aprobación humana</b>, y recién entonces falla al ejecutarse porque el
 * {@code program-id} estaba vacío. En este sistema lo caro no es fallar, es fallar después de que
 * una persona dijo sí.
 */
class AuthorityConfigurationTest {

    /** Una clave pública base58 real (el System Program: 32 bytes en cero, siempre válida). */
    private static final String VALID_ID = "11111111111111111111111111111111";

    @Configuration
    @EnableConfigurationProperties(AuthorityProperties.class)
    static class Ctx {}

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(Ctx.class, AuthorityConfiguration.class);

    @Test
    void apagadoPorDefectoNoCreaElBean() {
        // Sin el flag no hay bean. No es un bean nulo que alguien pueda inyectar por accidente:
        // directamente no existe, y quien lo necesite tiene que decidir qué hacer sin él.
        runner.run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx).doesNotHaveBean(IntentAuthorityProgram.class);
        });
    }

    @Test
    void apagadoExplicitoTampoco() {
        runner.withPropertyValues("cryptobot.authority.enabled=false",
                        "cryptobot.authority.program-id=" + VALID_ID)
                .run(ctx -> assertThat(ctx).doesNotHaveBean(IntentAuthorityProgram.class));
    }

    @Test
    void encendidoConProgramIdValidoCreaElBean() {
        runner.withPropertyValues("cryptobot.authority.enabled=true",
                        "cryptobot.authority.program-id=" + VALID_ID)
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(IntentAuthorityProgram.class);
                    assertThat(ctx.getBean(IntentAuthorityProgram.class).programId()).isEqualTo(VALID_ID);
                });
    }

    @Test
    void encendidoSinProgramIdNoArranca() {
        runner.withPropertyValues("cryptobot.authority.enabled=true")
                .run(ctx -> {
                    assertThat(ctx).hasFailed();
                    // El mensaje tiene que decir qué hacer, no sólo qué falta: el script de deploy es
                    // el único camino para conseguir el valor.
                    assertThat(ctx.getStartupFailure()).hasStackTraceContaining("deploy-devnet.sh");
                    assertThat(ctx.getStartupFailure()).hasStackTraceContaining("program-id");
                });
    }

    @Test
    void encendidoConProgramIdVacioNoArranca() {
        runner.withPropertyValues("cryptobot.authority.enabled=true", "cryptobot.authority.program-id=")
                .run(ctx -> assertThat(ctx).hasFailed());
    }

    @Test
    void encendidoConProgramIdInvalidoNoArranca() {
        // No es base58 de 32 bytes: un typo al copiar la salida del deploy no puede pasar silencioso.
        runner.withPropertyValues("cryptobot.authority.enabled=true",
                        "cryptobot.authority.program-id=no-es-una-clave")
                .run(ctx -> {
                    assertThat(ctx).hasFailed();
                    assertThat(ctx.getStartupFailure()).hasStackTraceContaining("base58");
                });
    }

    @Test
    void validSlotsCaeAlDefaultCuandoNoTieneSentido() {
        // Cero o negativo no es "sin expiración": sería I2 desactivada, o sea la invariante que el
        // programa existe para verificar, apagada por una config vacía.
        assertThat(new AuthorityProperties(true, VALID_ID, 1, 0).validSlots())
                .isEqualTo(AuthorityProperties.DEFAULT_VALID_SLOTS);
        assertThat(new AuthorityProperties(true, VALID_ID, 1, -5).validSlots())
                .isEqualTo(AuthorityProperties.DEFAULT_VALID_SLOTS);
        assertThat(new AuthorityProperties(true, VALID_ID, 1, 42).validSlots()).isEqualTo(42);
    }

    @Test
    void usableExigeLasDosCosas() {
        assertThat(new AuthorityProperties(true, VALID_ID, 1, 150).usable()).isTrue();
        assertThat(new AuthorityProperties(false, VALID_ID, 1, 150).usable()).isFalse();
        assertThat(new AuthorityProperties(true, null, 1, 150).usable()).isFalse();
        assertThat(new AuthorityProperties(true, "", 1, 150).usable()).isFalse();
        assertThat(new AuthorityProperties(true, "no-es-una-clave", 1, 150).usable()).isFalse();
    }
}
