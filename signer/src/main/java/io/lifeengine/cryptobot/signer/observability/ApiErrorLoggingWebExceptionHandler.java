package io.lifeengine.cryptobot.signer.observability;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.ErrorResponse;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebExceptionHandler;
import reactor.core.publisher.Mono;

/**
 * Deja UNA línea estructurada por cada excepción que llega al borde HTTP sin pasar por
 * el controller (404 de ruta, validación, 500 no manejado), y no toca la respuesta:
 * devuelve el error tal cual para que lo resuelva el handler de siempre. Sólo observa.
 *
 * <p>Un 5xx sale como ERROR con stack; un 4xx como WARN sin stack: el cliente se equivocó, no el
 * servicio.
 */
@Component
@Order(-3)
public class ApiErrorLoggingWebExceptionHandler implements WebExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiErrorLoggingWebExceptionHandler.class);

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable ex) {
        // Los handlers corren por fuera de la cadena de filtros: el requestId/tenantId del pedido se
        // recupera del exchange, no del Reactor Context.
        try (AutoCloseable ignored = LogContext.mdcFrom(exchange)) {
            logStructured(exchange, ex);
        } catch (Exception e) {
            // mdcFrom() no lanza; si alguna vez lo hiciera, el log de arriba ya salió o no importa.
        }
        return Mono.error(ex);
    }

    private static void logStructured(ServerWebExchange exchange, Throwable ex) {
        int status = statusOf(ex);
        ErrorCode code = ErrorCode.forStatus(status);
        String operationId = LogFields.operationIdOf(exchange);
        if (status >= 500) {
            log.error(
                    "api_error status={} errorCode={} operationId={} type={}",
                    status,
                    code.code(),
                    operationId,
                    ex.getClass().getName(),
                    LogFields.event("api_error"),
                    LogFields.status(status),
                    code.kv(),
                    LogFields.operationId(operationId),
                    ex);
        } else {
            log.warn(
                    "api_error status={} errorCode={} operationId={} type={}",
                    status,
                    code.code(),
                    operationId,
                    ex.getClass().getName(),
                    LogFields.event("api_error"),
                    LogFields.status(status),
                    code.kv(),
                    LogFields.operationId(operationId));
        }
    }

    static int statusOf(Throwable ex) {
        return ex instanceof ErrorResponse er ? er.getStatusCode().value() : 500;
    }
}
