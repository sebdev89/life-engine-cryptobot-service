package io.lifeengine.cryptobot.validator.observability;

import io.micrometer.context.ContextRegistry;
import io.micrometer.context.ThreadLocalAccessor;
import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.MDC;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.server.ServerWebExchange;
import reactor.util.context.Context;
import reactor.util.context.ContextView;

/**
 * Las claves de contexto que aparecen en cada línea de log del validatorNAME, y el mecanismo que las hace
 * viajar (KAN-573; copia del patrón de cryptobot-service / ATP). En WebFlux el MDC es un ThreadLocal y
 * un pedido no tiene un hilo: el único lugar donde se escribe es el Reactor Context, y con
 * {@code spring.reactor.context-propagation=auto} Reactor restaura el MDC alrededor de cada operador.
 *
 * <p>Sin {@code tenantId}: el validatorNAME no ve tenants, sólo bytes y un proposalId. Sin {@code operationId}:
 * la clave de idempotencia es del servicio. {@code proposalId} es lo que ata esta línea a la historia de
 * la propuesta en Loki ({@code | json | proposalId="…"}) junto con el traceId que llega en el traceparent.
 *
 * <p>Nunca {@code MDC.clear()}: borra también la correlación de la traza.
 */
@Configuration
public class LogContext {

    /** Identificador del pedido HTTP. Del header {@code X-Request-Id}, o generado. */
    public static final String REQUEST_ID = "requestId";

    /** Hilo de negocio que puede abarcar varios pedidos y servicios. Del header {@code X-Correlation-Id}. */
    public static final String CORRELATION_ID = "correlationId";

    /** La propuesta de cryptobot-service de la que habla el pedido. */
    public static final String PROPOSAL_ID = "proposalId";

    public static final List<String> KEYS = List.of(REQUEST_ID, CORRELATION_ID, PROPOSAL_ID);

    @PostConstruct
    void registerAccessors() {
        registerInto(ContextRegistry.getInstance());
    }

    /** Idempotente: {@code registerThreadLocalAccessor} reemplaza por clave. */
    public static void registerInto(ContextRegistry registry) {
        for (String key : KEYS) {
            registry.registerThreadLocalAccessor(new MdcKeyAccessor(key));
        }
    }

    /** Escribe en el Context sólo los valores presentes; un valor vacío no se propaga. */
    public static Context write(ContextView base, String key, String value) {
        return value == null || value.isBlank() ? Context.of(base) : Context.of(base).put(key, value);
    }

    private static final String EXCHANGE_ATTRIBUTE_PREFIX = "le.log.";

    /** Guarda el valor también como atributo del exchange, para los {@code WebExceptionHandler} que corren fuera de la cadena. */
    public static void attach(ServerWebExchange exchange, String key, String value) {
        if (value != null && !value.isBlank()) {
            exchange.getAttributes().put(EXCHANGE_ATTRIBUTE_PREFIX + key, value);
        }
    }

    /** Pone en el MDC, mientras dure el scope, lo que los filtros dejaron en el exchange. Sólo quita lo que agregó. */
    public static AutoCloseable mdcFrom(ServerWebExchange exchange) {
        List<String> added = new ArrayList<>(KEYS.size());
        for (String key : KEYS) {
            Object value = exchange.getAttributes().get(EXCHANGE_ATTRIBUTE_PREFIX + key);
            if (value instanceof String s && !s.isBlank() && MDC.get(key) == null) {
                MDC.put(key, s);
                added.add(key);
            }
        }
        return () -> added.forEach(MDC::remove);
    }

    /** {@code setValue()} sin argumentos es la restauración: quita la clave en vez de dejarla en blanco. */
    private record MdcKeyAccessor(String mdcKey) implements ThreadLocalAccessor<String> {

        @Override
        public Object key() {
            return mdcKey;
        }

        @Override
        public String getValue() {
            return MDC.get(mdcKey);
        }

        @Override
        public void setValue(String value) {
            MDC.put(mdcKey, value);
        }

        @Override
        public void setValue() {
            MDC.remove(mdcKey);
        }
    }
}
