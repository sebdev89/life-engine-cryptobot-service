package io.lifeengine.cryptobot.observability;

import net.logstash.logback.argument.StructuredArgument;
import net.logstash.logback.argument.StructuredArguments;
import org.springframework.web.reactive.HandlerMapping;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.util.pattern.PathPattern;

/**
 * Los campos estructurados comunes que un log de negocio agrega a la línea JSON (KAN-426). Van como
 * {@link StructuredArgument}: en JSON salen como campos de primer nivel; en texto, como
 * {@code clave=valor} al final del mensaje.
 *
 * <pre>{@code
 * log.warn("proposal_execution_failed …", LogFields.event("execution_failed"), ErrorCode.EXECUTION_FAILED.kv(), LogFields.stage("sign"));
 * }</pre>
 */
public final class LogFields {

    /** Qué pasó, en forma estable y buscable ({@code rag.query}, {@code api_error}). */
    public static final String EVENT = "event";
    /** Status HTTP o resultado de la operación ({@code ok}, {@code failed}). */
    public static final String STATUS = "status";
    /** La operación lógica: la ruta declarada ({@code POST /api/rag/query}), no la URL con ids. */
    public static final String OPERATION_ID = "operationId";

    private LogFields() {}

    public static StructuredArgument event(String event) {
        return StructuredArguments.kv(EVENT, event);
    }

    public static StructuredArgument status(int httpStatus) {
        return StructuredArguments.kv(STATUS, httpStatus);
    }

    public static StructuredArgument status(String status) {
        return StructuredArguments.kv(STATUS, status);
    }

    public static StructuredArgument operationId(String operationId) {
        return StructuredArguments.kv(OPERATION_ID, operationId);
    }

    /** En qué paso del pipeline pasó ({@code preflight, validate, sign, broadcast, onchain, rpc, reconciliation}). */
    public static final String STAGE = "stage";

    public static StructuredArgument stage(String stage) {
        return StructuredArguments.kv(STAGE, stage == null ? "unknown" : stage.toLowerCase(java.util.Locale.ROOT));
    }

    /**
     * {@code proposalId} como argumento estructurado, para las líneas que salen fuera de la cadena
     * reactiva que lleva el MDC (el publisher del outbox, un handler de errores). Dentro del
     * pipeline no hace falta: {@link LogContext#PROPOSAL_ID} ya está en cada línea.
     */
    public static StructuredArgument proposalId(java.util.UUID proposalId) {
        return StructuredArguments.kv(LogContext.PROPOSAL_ID, proposalId == null ? null : proposalId.toString());
    }

    /**
     * {@code MÉTODO patrón-de-ruta} del handler que atendió el pedido, o {@code MÉTODO path} si
     * el pedido no llegó a un handler (401 en un filtro, 404). El patrón, no la URL: la cardinalidad
     * de {@code /api/.../{id}} es una; la de las URLs, una por documento.
     */
    public static String operationIdOf(ServerWebExchange exchange) {
        Object pattern = exchange.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        String path = exchange.getRequest().getPath().value();
        // "/**" es el handler de recursos estáticos que atrapa los 404: ahí el patrón no dice nada
        // y el path sí.
        String route =
                pattern instanceof PathPattern p && !"/**".equals(p.getPatternString()) ? p.getPatternString() : path;
        return exchange.getRequest().getMethod().name() + " " + route;
    }
}
