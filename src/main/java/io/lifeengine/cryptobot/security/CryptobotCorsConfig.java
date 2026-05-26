package io.lifeengine.cryptobot.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.cors.reactive.CorsWebFilter;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

/**
 * Permissive local CORS for {@code http://localhost:4201..4203}. Production must override with
 * {@code CRYPTOBOT_HTTP_CORS_ALLOWED_ORIGINS} (comma-separated) once the deployment story exists.
 */
@Configuration
public class CryptobotCorsConfig {

    @Bean
    CorsWebFilter cryptobotCorsWebFilter() {
        CorsConfiguration cors = new CorsConfiguration();
        cors.addAllowedOriginPattern("http://localhost:*");
        cors.addAllowedOriginPattern("http://127.0.0.1:*");
        cors.addAllowedMethod("*");
        cors.addAllowedHeader("*");
        cors.setExposedHeaders(java.util.List.of("Content-Type"));
        cors.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", cors);
        return new CorsWebFilter((CorsConfigurationSource) source);
    }
}
