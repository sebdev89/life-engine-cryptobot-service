package io.lifeengine.cryptobot.observability;

import io.micrometer.context.ContextRegistry;
import io.micrometer.context.ThreadLocalAccessor;
import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.server.ServerWebExchange;
import reactor.util.context.Context;
import reactor.util.context.ContextView;

/**
 * Las claves de contexto que aparecen en cada línea de log, y el mecanismo que las hace viajar
 * (KAN-426 / KAN-573; el modelo es el de life-engine-runtime, copiado de ATP/Dev Agent).
 *
 * <p>En una app WebFlux el MDC de SLF4J es un {@code ThreadLocal} y un pedido reactivo no tiene un
 * hilo. Escribirle al MDC en un punto de la cadena no hace que el valor exista en el siguiente. Por
 * eso el único lugar donde se escribe es el Reactor Context; cada clave se registra como
 * {@link ThreadLocalAccessor} y, con {@code spring.reactor.context-propagation=auto}, Reactor
 * restaura el MDC alrededor de cada operador, en el hilo que le toque. Es la misma vía por la que
 * Micrometer hace viajar {@code traceId}/{@code spanId}.
 *
 * <p>Además de las tres claves de la plataforma, CryptoBot lleva las del demo path: una línea de
 * {@code ExecutionService}, {@code ReconciliationService} o {@code DeadLetterService} dice de qué
 * propuesta y de qué operación (la clave de idempotencia) habla sin que cada log tenga que
 * repetirlo, y en Loki {@code | json | proposalId="…"} devuelve la historia completa de una
 * propuesta: policy → intent → tx → confirmación → reconciliación → DLQ.
 *
 * <p>Nunca {@code MDC.clear()}: borra también la correlación de la traza.
 */
@Configuration
public class LogContext {

    /** Identificador del pedido HTTP. Del header {@code X-Request-Id}, o generado. */
    public static final String REQUEST_ID = "requestId";

    /** Hilo de negocio que puede abarcar varios pedidos y servicios. Del header {@code X-Correlation-Id}. */
    public static final String CORRELATION_ID = "correlationId";

    /**
     * Tenant del llamador, <b>resuelto server-side</b> del token. En CryptoBot el tenant de una
     * wallet, una propuesta y un recibo es su {@code ownerUserId} ({@code Receipts.tenantOf}), así
     * que es el {@code sub} del JWT verificado. Nunca de un header del cliente: un tenant afirmado
     * por el cliente en el log es evidencia falsa.
     */
    public static final String TENANT_ID = "tenantId";

    /** La propuesta ({@code ActionProposal}) de la que habla la línea: el hilo del demo path. */
    public static final String PROPOSAL_ID = "proposalId";

    /**
     * La clave de idempotencia de una ejecución ({@code Idempotency-Key} / intent hash): la misma en
     * el primer intento, en el retry del reconciliador y en el requeue desde la DLQ. No confundir
     * con el {@code operationId} de {@link LogFields} (la ruta lógica de un {@code api_error}):
     * ese va como argumento estructurado sólo en esa línea, y esta clave sólo dentro del pipeline.
     */
    public static final String OPERATION_ID = "operationId";

    /** La corrida del Runtime (advisor / market review) que produjo el análisis. */
    public static final String RUNTIME_RUN_ID = "runtimeRunId";

    public static final List<String> KEYS = List.of(REQUEST_ID, CORRELATION_ID, TENANT_ID, PROPOSAL_ID, OPERATION_ID, RUNTIME_RUN_ID);

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

    public static Context write(ContextView base, String key, UUID value) {
        return write(base, key, value == null ? null : value.toString());
    }

    /** {@code proposalId} + {@code operationId} de una vez: lo que toda línea del pipeline lleva. */
    public static Context proposal(ContextView base, UUID proposalId, UUID operationId) {
        return write(write(base, PROPOSAL_ID, proposalId), OPERATION_ID, operationId);
    }

    private static final String EXCHANGE_ATTRIBUTE_PREFIX = "le.log.";

    /**
     * Guarda el valor también como atributo del exchange. El Reactor Context de los filtros no llega
     * a los {@code WebExceptionHandler}, que corren por fuera de la cadena; el exchange sí.
     */
    public static void attach(ServerWebExchange exchange, String key, String value) {
        if (value != null && !value.isBlank()) {
            exchange.getAttributes().put(EXCHANGE_ATTRIBUTE_PREFIX + key, value);
        }
    }

    /**
     * Pone en el MDC, mientras dure el scope, lo que los filtros dejaron en el exchange: para loguear
     * desde un {@code WebExceptionHandler} con el mismo requestId/tenantId que el resto del pedido.
     * Sólo agrega lo que falta y sólo quita lo que agregó: nunca toca traceId/spanId.
     */
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

    /**
     * {@code setValue()} sin argumentos es la restauración: quita la clave en vez de dejarla en
     * blanco, para que el próximo trabajo del pool no loggee un id ajeno o vacío.
     */
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
