package io.lifeengine.cryptobot.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.cors.reactive.CorsUtils;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/** Validates Bearer JWT before authorization. Skips public health/metrics endpoints. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class CryptobotJwtAuthenticationWebFilter implements WebFilter {

    private static final Logger log = LoggerFactory.getLogger(CryptobotJwtAuthenticationWebFilter.class);

    private final CryptobotJwtService jwtService;
    private final CryptobotSecurityProperties securityProperties;
    private final ObjectMapper objectMapper;

    public CryptobotJwtAuthenticationWebFilter(
            CryptobotJwtService jwtService,
            CryptobotSecurityProperties securityProperties,
            ObjectMapper objectMapper) {
        this.jwtService = jwtService;
        this.securityProperties = securityProperties;
        this.objectMapper = objectMapper;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (!securityProperties.enabled() || shouldSkip(exchange)) {
            return chain.filter(exchange);
        }
        String auth = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        var outcome = jwtService.parseAuthorizationHeader(auth);
        if (outcome.principal().isEmpty()) {
            String reason = outcome.failureReason().orElse("unknown");
            log.warn(
                    "cryptobot_jwt rejected path={} method={} reason={}",
                    exchange.getRequest().getPath().value(),
                    exchange.getRequest().getMethod(),
                    reason);
            return writeJson(exchange, HttpStatus.UNAUTHORIZED, "unauthorized", "Authentication required");
        }
        CryptobotPrincipal principal = outcome.principal().orElseThrow();
        var authorities =
                principal.authorities().stream()
                        .map(SimpleGrantedAuthority::new)
                        .collect(Collectors.toList());
        var authentication = new UsernamePasswordAuthenticationToken(principal, null, authorities);
        return chain.filter(exchange)
                .contextWrite(ReactiveSecurityContextHolder.withAuthentication(authentication));
    }

    static boolean shouldSkip(ServerWebExchange exchange) {
        if (CorsUtils.isPreFlightRequest(exchange.getRequest())) {
            return true;
        }
        String path = exchange.getRequest().getPath().value();
        return path.startsWith("/actuator/health")
                || path.equals("/api/cryptobot/health")
                || path.equals("/actuator/prometheus");
    }

    private Mono<Void> writeJson(ServerWebExchange exchange, HttpStatus status, String code, String message) {
        exchange.getResponse().setStatusCode(status);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        try {
            byte[] body = objectMapper.writeValueAsBytes(new ApiErrorBody(code, message));
            return exchange.getResponse().writeWith(Mono.just(exchange.getResponse().bufferFactory().wrap(body)));
        } catch (Exception ex) {
            return Mono.error(ex);
        }
    }

    private record ApiErrorBody(String code, String message) {}
}
