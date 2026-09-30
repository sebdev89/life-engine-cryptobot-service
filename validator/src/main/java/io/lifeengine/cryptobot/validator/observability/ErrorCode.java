package io.lifeengine.cryptobot.validator.observability;

import net.logstash.logback.argument.StructuredArgument;
import net.logstash.logback.argument.StructuredArguments;

/**
 * Catálogo de errores del validatorNAME. Corto a propósito: un código por causa que
 * alguien va a buscar en Loki durante el demo. Formato {@code CB-VALIDATOR-<NNN>}; los de borde
 * ({@code HTTP}, {@code INTERNAL}) son los mismos de la plataforma.
 *
 * <p>Se loguea como campo {@code errorCode} ({@link #kv()}): {@code {service="cryptobot-validator"} | json |
 * errorCode="CB-VALIDATOR-001"} devuelve exactamente las líneas de esa causa.
 */
public enum ErrorCode {
    /** Veredicto DENY o rechazo del validador (deshabilitado, hash de policy distinto, desacuerdo con el servicio). */
    DENIED("CB-VALIDATOR-001", "validación denegada"),
    /** {@code X-Validator-Token} ausente o incorrecto. */
    BAD_TOKEN("CB-VALIDATOR-002", "token de servicio rechazado"),
    /** Pedido de validación malformado (intent/state/cluster inválidos). */
    MALFORMED("CB-VALIDATOR-003", "pedido de validación malformado"),

    /** Pedido inválido (validación, cuerpo malformado). */
    HTTP_400("CB-HTTP-400", "pedido inválido"),
    HTTP_401("CB-HTTP-401", "no autenticado"),
    HTTP_403("CB-HTTP-403", "no autorizado"),
    HTTP_404("CB-HTTP-404", "no encontrado"),
    /** Cualquier otro 4xx. */
    HTTP_4XX("CB-HTTP-4XX", "rechazado por el cliente"),
    /** Excepción no manejada: el 500 genérico. */
    INTERNAL("CB-INTERNAL-500", "error interno");

    /** Nombre del campo en el log JSON. */
    public static final String FIELD = "errorCode";

    private final String code;
    private final String description;

    ErrorCode(String code, String description) {
        this.code = code;
        this.description = description;
    }

    public String code() {
        return code;
    }

    public String description() {
        return description;
    }

    /** El argumento estructurado listo para pasarle a un logger: {@code log.warn("…", code.kv())}. */
    public StructuredArgument kv() {
        return StructuredArguments.kv(FIELD, code);
    }

    /** Código para un status HTTP que no tiene una causa más específica. */
    public static ErrorCode forStatus(int status) {
        if (status >= 500) {
            return INTERNAL;
        }
        return switch (status) {
            case 400 -> HTTP_400;
            case 401 -> HTTP_401;
            case 403 -> HTTP_403;
            case 404 -> HTTP_404;
            default -> status >= 400 ? HTTP_4XX : INTERNAL;
        };
    }
}
