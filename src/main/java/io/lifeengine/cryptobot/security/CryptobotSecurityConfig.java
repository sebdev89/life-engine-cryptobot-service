package io.lifeengine.cryptobot.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Q4 decision: Phase 1 reuses {@code RUNTIME_OPERATOR} authority to gate the market-review entry
 * point. A dedicated {@code CRYPTOBOT_OPERATOR} can be introduced in Phase 2 by adding it here and
 * granting via a new {@code auth_role_permission} migration in {@code life-engine-auth}.
 */
@Configuration
@EnableWebFluxSecurity
public class CryptobotSecurityConfig {

    public static final String AUTHORITY_MARKET_REVIEW = "RUNTIME_OPERATOR";

    @Bean
    SecurityWebFilterChain cryptobotSecurityWebFilterChain(
            ServerHttpSecurity http, CryptobotSecurityProperties securityProperties) {
        if (!securityProperties.enabled()) {
            return http.csrf(ServerHttpSecurity.CsrfSpec::disable)
                    .authorizeExchange(ex -> ex.anyExchange().permitAll())
                    .build();
        }
        return http.csrf(ServerHttpSecurity.CsrfSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .exceptionHandling(
                        ex ->
                                ex.authenticationEntryPoint(
                                                (exchange, e) -> {
                                                    exchange.getResponse()
                                                            .setStatusCode(org.springframework.http.HttpStatus.UNAUTHORIZED);
                                                    return Mono.empty();
                                                })
                                        .accessDeniedHandler(
                                                (exchange, denied) -> {
                                                    exchange.getResponse()
                                                            .setStatusCode(org.springframework.http.HttpStatus.FORBIDDEN);
                                                    return Mono.empty();
                                                }))
                .authorizeExchange(
                        auth ->
                                auth.pathMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/**")
                                        .permitAll()
                                        .pathMatchers(HttpMethod.GET, "/api/cryptobot/health")
                                        .permitAll()
                                        .pathMatchers(HttpMethod.GET, "/actuator/prometheus")
                                        .permitAll()
                                        .pathMatchers(HttpMethod.POST, "/api/cryptobot/market-review")
                                        .hasAuthority(AUTHORITY_MARKET_REVIEW)
                                        .pathMatchers("/api/cryptobot/**")
                                        .hasAuthority(AUTHORITY_MARKET_REVIEW)
                                        .anyExchange()
                                        .denyAll())
                .build();
    }
}
