package io.lifeengine.cryptobot.signer.observability;

import java.util.UUID;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Propaga {@code X-Request-Id} / {@code X-Correlation-Id} al Reactor Context (y de ahí al MDC, ver
 * {@link LogContext}) y los devuelve en la respuesta, para que el cliente y el servidor puedan
 * hablar del mismo pedido. Un header ausente se genera; uno presente se respeta, que es lo que
 * permite seguir un hilo que cruza servicios.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestCorrelationWebFilter implements WebFilter {

    static final String REQUEST_ID_HEADER = "X-Request-Id";
    static final String CORRELATION_ID_HEADER = "X-Correlation-Id";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String requestId = headerOrNew(exchange, REQUEST_ID_HEADER);
        String correlationId = headerOrNew(exchange, CORRELATION_ID_HEADER);
        exchange.getResponse().getHeaders().set(REQUEST_ID_HEADER, requestId);
        exchange.getResponse().getHeaders().set(CORRELATION_ID_HEADER, correlationId);
        LogContext.attach(exchange, LogContext.REQUEST_ID, requestId);
        LogContext.attach(exchange, LogContext.CORRELATION_ID, correlationId);
        return chain.filter(exchange)
                .contextWrite(
                        ctx -> ctx.put(LogContext.REQUEST_ID, requestId).put(LogContext.CORRELATION_ID, correlationId));
    }

    private static String headerOrNew(ServerWebExchange exchange, String name) {
        String value = exchange.getRequest().getHeaders().getFirst(name);
        if (value != null && !value.isBlank() && value.length() <= 128) {
            return value.trim();
        }
        return UUID.randomUUID().toString();
    }
}
