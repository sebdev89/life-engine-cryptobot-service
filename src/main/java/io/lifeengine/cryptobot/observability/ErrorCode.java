package io.lifeengine.cryptobot.observability;

import net.logstash.logback.argument.StructuredArgument;
import net.logstash.logback.argument.StructuredArguments;

/**
 * Catálogo de errores de CryptoBot. Corto a propósito: un código por causa que
 * alguien va a buscar en Loki durante el demo, no uno por excepción. Formato
 * {@code CB-<ÁREA>-<NNN>}; las áreas siguen el demo path — {@code POLICY} → {@code RISK} →
 * {@code EXEC}/{@code SOLANA} → {@code RECON} → {@code DLQ} — más las de borde ({@code AUTH},
 * {@code RUNTIME}, {@code HTTP}, {@code INTERNAL}).
 *
 * <p>Se loguea como campo {@code errorCode} ({@link #kv()}), así en Loki
 * {@code {service="cryptobot-service"} | json | errorCode="CB-DLQ-001"} devuelve exactamente las
 * líneas de esa causa, y con {@code | json | proposalId="…"} la historia de una propuesta. El código
 * no es el mensaje: el mensaje puede cambiar, el código no.
 */
public enum ErrorCode {
    // ---- policy: la propuesta no llega al humano, o la ejecución no arranca --------------------
    /** La policy determinista bloqueó la propuesta ({@code BLOCKED_BY_POLICY}: allowlist, tope, cooldown, DENY de autorización). */
    POLICY_BLOCKED("CB-POLICY-001", "propuesta bloqueada por policy"),
    /** Precondición de ejecución no cumplida (kill switch, timelock, cluster, validador ausente, estado). */
    EXECUTION_PRECONDITION("CB-POLICY-002", "precondición de ejecución no cumplida"),
    /** Mainnet fail-closed: nada se firmó ni se envió. */
    MAINNET_DISABLED("CB-POLICY-003", "mainnet deshabilitado"),

    // ---- risk ---------------------------------------------------------------------------------
    /** El motor de riesgo determinista calificó el portfolio resultante como HIGH. */
    RISK_HIGH("CB-RISK-001", "riesgo alto"),
    /** El oráculo de precios rechazó la ejecución (quórum, desvío, breaker). */
    ORACLE_REFUSED("CB-RISK-002", "oráculo rechazó la ejecución"),

    // ---- execution: intent → validador → signer → broadcast -------------------------------------
    /** El pipeline falló antes de que la cadena pudiera ver la tx ({@code stage} dice dónde: preflight, validate, sign, rpc). */
    EXECUTION_FAILED("CB-EXEC-001", "ejecución fallida antes del broadcast"),
    /** {@code sendTransaction} no devolvió firma y no fue un rechazo del nodo: la tx puede estar en la red. */
    BROADCAST_UNCERTAIN("CB-EXEC-002", "broadcast incierto"),
    /** El validador independiente no respondió o rechazó (DENY, desacuerdo, hash de policy). */
    VALIDATOR_UNAVAILABLE("CB-EXEC-003", "validador independiente inaccesible o rechazó"),
    /** El signer aislado no respondió o rechazó. */
    SIGNER_UNAVAILABLE("CB-EXEC-004", "signer inaccesible o rechazó"),

    // ---- solana -------------------------------------------------------------------------------
    /** El RPC de Solana falló (error JSON-RPC, timeout, transporte). */
    SOLANA_RPC("CB-SOLANA-001", "rpc de solana falló"),
    /** La transacción llegó a la cadena y falló ahí. */
    SOLANA_TX_FAILED("CB-SOLANA-002", "transacción fallida on-chain"),

    // ---- reconciliation -----------------------------------------------------------------------
    /** Una fila SUBMITTED cuya firma la cadena nunca vio (blockhash vencido). */
    RECONCILIATION_MISMATCH("CB-RECON-001", "submitted pero nunca vista en la cadena"),
    /** Retry idempotente: la misma operación se re-ejecuta con blockhash nuevo. */
    RECONCILIATION_RETRY("CB-RECON-002", "retry idempotente de la ejecución"),
    /** La reconciliación de una fila terminó en error y se saltó hasta el próximo barrido. */
    RECONCILIATION_ROW_FAILED("CB-RECON-003", "reconciliación de una fila falló"),

    // ---- dead letter queue --------------------------------------------------------------------
    /** Se creó una carta muerta: decisión humana requerida (ambiguous, retries_exhausted, inconsistent). */
    DEAD_LETTERED("CB-DLQ-001", "propuesta en la DLQ"),
    /** El requeue desde la DLQ no pudo reconciliar; la fila queda para el barrido. */
    DLQ_REQUEUE_FAILED("CB-DLQ-002", "requeue desde la DLQ no reconcilió"),
    /** Un evento del outbox agotó sus reintentos de publicación. */
    OUTBOX_DEAD("CB-DLQ-003", "evento del outbox muerto"),

    // ---- borde --------------------------------------------------------------------------------
    /** El Runtime (advisor / market review) no respondió. */
    RUNTIME_UNREACHABLE("CB-RUNTIME-001", "runtime inaccesible"),
    /** Token ausente, malformado, vencido o con firma inválida. */
    AUTH_TOKEN("CB-AUTH-001", "token rechazado"),
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
