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
    /** Opening and submitting an anchoring batch on devnet is an admin/cron action, not an operator's. */
    public static final String AUTHORITY_ANCHOR_ADMIN = "RUNTIME_ADMIN";

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
                                        // Build identity. Internal-network readable like the
                                        // other scrape endpoints; nginx keeps /actuator/info blocked
                                        // externally (LIFE-OPS-02 §2.2). Only /actuator/info is opened —
                                        // /actuator/metrics and the rest stay denied. Carries no secrets.
                                        .pathMatchers(HttpMethod.GET, "/actuator/info")
                                        .permitAll()
                                        // the OpenAPI schema is documentation, not data — same
                                        // treatment as health/prometheus/info, never behind an authority.
                                        .pathMatchers(HttpMethod.GET, "/v3/api-docs", "/v3/api-docs/**",
                                                "/swagger-ui.html", "/swagger-ui/**")
                                        .permitAll()
                                        .pathMatchers(HttpMethod.POST, "/api/cryptobot/market-review")
                                        .hasAuthority(AUTHORITY_MARKET_REVIEW)
                                        .pathMatchers(HttpMethod.POST, "/api/cryptobot/monitoring/**")
                                        .hasAuthority(AUTHORITY_MARKET_REVIEW)
                                        .pathMatchers(HttpMethod.GET, "/api/cryptobot/market-reviews/**")
                                        .hasAuthority(AUTHORITY_MARKET_REVIEW)
                                        .pathMatchers(HttpMethod.POST, "/api/cryptobot/anchors")
                                        .hasAuthority(AUTHORITY_ANCHOR_ADMIN)
                                        // the global DLQ and its resolution are an admin's, not an operator's.
                                        // an immediate reward moves devnet funds (the controller checks it too).
                                        .pathMatchers(HttpMethod.POST, "/api/cryptobot/value-events/*/distribute")
                                        .hasAuthority(AUTHORITY_ANCHOR_ADMIN)
                                        // a revenue event pays its contributor pool (devnet funds) too.
                                        .pathMatchers(HttpMethod.POST, "/api/cryptobot/revenue-events")
                                        .hasAuthority(AUTHORITY_ANCHOR_ADMIN)
                                        .pathMatchers("/api/cryptobot/dead-letters/**")
                                        .hasAuthority(AUTHORITY_ANCHOR_ADMIN)
                                        // fault injection exists only in the demo stack (cryptobot.chaos.enabled); admin there too.
                                        .pathMatchers("/api/cryptobot/demo/**")
                                        .hasAuthority(AUTHORITY_ANCHOR_ADMIN)
                                        .pathMatchers("/api/cryptobot/**")
                                        .hasAuthority(AUTHORITY_MARKET_REVIEW)
                                        .anyExchange()
                                        .denyAll())
                .build();
    }
}
