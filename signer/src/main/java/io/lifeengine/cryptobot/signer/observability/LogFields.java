package io.lifeengine.cryptobot.signer.observability;

import net.logstash.logback.argument.StructuredArgument;
import net.logstash.logback.argument.StructuredArguments;
import org.springframework.web.reactive.HandlerMapping;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.util.pattern.PathPattern;

/**
 * Los campos estructurados comunes que un log de negocio agrega a la línea JSON (KAN-426 / KAN-573).
 * Van como {@link StructuredArgument}: en JSON salen como campos de primer nivel; en texto, como
 * {@code clave=valor} al final del mensaje.
 */
public final class LogFields {

    /** Qué pasó, en forma estable y buscable ({@code sign_refused}, {@code api_error}). */
    public static final String EVENT = "event";
    /** Status HTTP o resultado de la operación ({@code signed}, {@code refused}). */
    public static final String STATUS = "status";
    /** La operación lógica: la ruta declarada ({@code POST /api/signer/sign}), no la URL con ids. */
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

    /** {@code MÉTODO patrón-de-ruta} del handler, o {@code MÉTODO path} si el pedido no llegó a un handler. */
    public static String operationIdOf(ServerWebExchange exchange) {
        Object pattern = exchange.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        String path = exchange.getRequest().getPath().value();
        String route =
                pattern instanceof PathPattern p && !"/**".equals(p.getPatternString()) ? p.getPatternString() : path;
        return exchange.getRequest().getMethod().name() + " " + route;
    }
}
