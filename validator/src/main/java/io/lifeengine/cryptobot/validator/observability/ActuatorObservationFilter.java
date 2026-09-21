package io.lifeengine.cryptobot.validator.observability;

import io.micrometer.observation.ObservationPredicate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.reactive.observation.ServerRequestObservationContext;

/**
 * Las sondas ({@code /actuator/health*}, {@code /actuator/prometheus}) no generan observaciones ni
 * spans. Medido en KAN-397: el 100 % de las trazas que había en Jaeger eran sondas, y tapaban las
 * pocas trazas reales. Las métricas de esas rutas tampoco dicen nada que Prometheus no sepa ya.
 */
@Configuration
class ActuatorObservationFilter {

    @Bean
    ObservationPredicate skipActuatorObservations() {
        return (name, context) ->
                !(context instanceof ServerRequestObservationContext http
                        && http.getCarrier().getPath().value().startsWith("/actuator/"));
    }
}
