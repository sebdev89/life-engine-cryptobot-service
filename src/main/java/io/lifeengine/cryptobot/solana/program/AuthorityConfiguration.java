package io.lifeengine.cryptobot.solana.program;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Construye el cliente del programa on-chain, o falla al arrancar (KAN-752).
 *
 * <p>La decisión que importa acá es <b>cuándo</b> se descubre una mala configuración. Con
 * {@code enabled=true} y un {@code program-id} ausente o inválido, el contexto no levanta: el
 * arranque es el único momento en que alguien está mirando. Descubrirlo en el primer request
 * significaría una propuesta aprobada que falla al ejecutarse, y el modo de falla más caro de este
 * sistema es el que aparece <b>después</b> de la aprobación humana.
 *
 * <p>Con {@code enabled=false} el bean no se crea —{@code @ConditionalOnProperty}, no un
 * {@code return null}—. Un bean nulo es un objeto que alguien puede inyectar y usar por accidente;
 * la ausencia, en cambio, obliga a declarar la dependencia como {@code Optional} y a decidir
 * explícitamente qué hacer cuando el programa no está desplegado.
 */
@Configuration
public class AuthorityConfiguration {

    private static final Logger log = LoggerFactory.getLogger(AuthorityConfiguration.class);

    @Bean
    @ConditionalOnProperty(prefix = "cryptobot.authority", name = "enabled", havingValue = "true")
    IntentAuthorityProgram intentAuthorityProgram(AuthorityProperties properties) {
        if (properties.programId() == null || properties.programId().isBlank()) {
            throw new IllegalStateException(
                    "cryptobot.authority.enabled=true sin cryptobot.authority.program-id: no hay a qué "
                            + "dirección invocar. Desplegá el programa con "
                            + "programs/intent-authority/scripts/deploy-devnet.sh y pasá el program id que imprime.");
        }
        if (!properties.usable()) {
            throw new IllegalStateException(
                    "cryptobot.authority.program-id no es una clave pública base58 válida ("
                            + properties.programId().length() + " caracteres): revisá el valor que imprimió el deploy.");
        }
        IntentAuthorityProgram program = new IntentAuthorityProgram(properties.programId());
        log.info("intent_authority_enabled program_id={} policy_version={} valid_slots={}",
                program.programId(), properties.policyVersion(), properties.validSlots());
        return program;
    }
}
