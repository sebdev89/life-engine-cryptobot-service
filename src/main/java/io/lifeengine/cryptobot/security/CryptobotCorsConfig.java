package io.lifeengine.cryptobot.security;

import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.cors.reactive.CorsWebFilter;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

/**
 * Configures CORS for the cryptobot-service so the cryptobot-ui (and other Life Engine UIs running
 * on local dev ports) can call the API from the browser.
 *
 * <p>The {@link CorsWebFilter} is registered at {@link Ordered#HIGHEST_PRECEDENCE} so that it runs
 * <em>before</em> {@link CryptobotJwtAuthenticationWebFilter} ({@code HIGHEST_PRECEDENCE + 10}).
 * Browser preflight ({@code OPTIONS}) requests are short-circuited by {@code CorsWebFilter} with a
 * {@code 204} CORS response; they never reach the JWT filter and therefore do not require an
 * {@code Authorization} header. Non-preflight requests still flow through the JWT filter and the
 * Spring Security filter chain unchanged — auth is not weakened for real API calls.
 *
 * <p>Allowed origins default to the local UI ports listed in {@link #DEFAULT_ALLOWED_ORIGINS} and
 * can be overridden in any environment via {@code cryptobot.http.cors.allowed-origins} (or the
 * {@code CRYPTOBOT_HTTP_CORS_ALLOWED_ORIGINS} env var) as a comma-separated list.
 */
@Configuration
public class CryptobotCorsConfig {

    static final List<String> DEFAULT_ALLOWED_ORIGINS = List.of(
            "http://127.0.0.1:4204",
            "http://localhost:4204",
            "http://127.0.0.1:4202",
            "http://localhost:4202");

    private static final List<String> ALLOWED_METHODS = List.of(
            HttpMethod.GET.name(),
            HttpMethod.POST.name(),
            HttpMethod.PUT.name(),
            HttpMethod.PATCH.name(),
            HttpMethod.DELETE.name(),
            HttpMethod.OPTIONS.name());

    private static final List<String> ALLOWED_HEADERS = List.of(
            HttpHeaders.AUTHORIZATION, HttpHeaders.CONTENT_TYPE, HttpHeaders.ACCEPT);

    private final List<String> allowedOrigins;

    public CryptobotCorsConfig(
            @Value("${cryptobot.http.cors.allowed-origins:}") String allowedOriginsCsv) {
        this.allowedOrigins = parseOrigins(allowedOriginsCsv);
    }

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    CorsWebFilter cryptobotCorsWebFilter() {
        CorsConfiguration cors = new CorsConfiguration();
        cors.setAllowedOrigins(allowedOrigins);
        cors.setAllowedMethods(ALLOWED_METHODS);
        cors.setAllowedHeaders(ALLOWED_HEADERS);
        cors.setExposedHeaders(List.of(HttpHeaders.CONTENT_TYPE));
        cors.setAllowCredentials(true);
        cors.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", cors);
        return new CorsWebFilter((CorsConfigurationSource) source);
    }

    private static List<String> parseOrigins(String csv) {
        if (csv == null || csv.isBlank()) {
            return DEFAULT_ALLOWED_ORIGINS;
        }
        List<String> parsed = Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
        return parsed.isEmpty() ? DEFAULT_ALLOWED_ORIGINS : parsed;
    }
}
