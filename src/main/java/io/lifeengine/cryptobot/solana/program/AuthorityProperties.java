package io.lifeengine.cryptobot.solana.program;

import io.lifeengine.cryptobot.solana.rpc.Base58;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuración del programa on-chain {@code intent-authority} (KAN-752).
 *
 * <p>El nivel 4 del paper (KAN-437) dice que las cuatro invariantes las verifica <b>el validador de
 * Solana</b>, no el servicio. Hoy eso sólo es cierto dentro de {@code solana-program-test}: el
 * programa está escrito y probado, pero no está desplegado y nada en el camino de ejecución lo
 * invoca. Estas propiedades son el primer paso: el {@code program-id} entra por configuración —nunca
 * hardcodeado— porque el programa deriva cada PDA del {@code program_id} con que lo invocan y por eso
 * no declara uno fijo.
 *
 * <p><b>Apagado por defecto, a propósito.</b> Mientras el programa no esté desplegado en devnet no
 * hay nada que invocar, y un default encendido dejaría al servicio construyendo instrucciones contra
 * una dirección inexistente. Con {@code enabled=false} el comportamiento es exactamente el de hoy.
 *
 * @param enabled si el servicio compone la instrucción {@code Execute} en la transacción
 * @param programId la dirección del programa desplegado, base58. Pública: no es un secreto
 * @param policyVersion la versión de política cuyo PDA registró la autoridad
 * @param validSlots ventana de expiración (I2) en slots desde el slot actual
 */
@ConfigurationProperties(prefix = "cryptobot.authority")
public record AuthorityProperties(boolean enabled, String programId, long policyVersion, int validSlots) {

    /** ~60 s a 400 ms por slot. Suficiente para aprobación humana + firma, corto para un replay. */
    public static final int DEFAULT_VALID_SLOTS = 150;

    public AuthorityProperties {
        if (validSlots <= 0) {
            validSlots = DEFAULT_VALID_SLOTS;
        }
    }

    /**
     * Si la configuración alcanza para invocar el programa. Se exige el {@code program-id} válido
     * <b>además</b> del flag: un {@code enabled=true} sin dirección no es "casi listo", es una
     * configuración que no puede funcionar, y se rechaza al arrancar (ver
     * {@link AuthorityConfiguration}) en vez de en el primer request.
     */
    public boolean usable() {
        return enabled && programId != null && Base58.isPublicKey(programId);
    }
}
