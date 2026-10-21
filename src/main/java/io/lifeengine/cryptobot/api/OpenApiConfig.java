package io.lifeengine.cryptobot.api;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * (TAE fase 2, mandato §28): "OpenAPI (springdoc) publicado" — sólo metadata (título,
 * versión, el esquema Bearer que ya exige {@code CryptobotJwtAuthenticationWebFilter}); ninguna
 * ruta ni ningún DTO se toca acá. springdoc descubre los controllers por reflexión.
 */
@Configuration
public class OpenApiConfig {

    private static final String BEARER = "bearer-jwt";

    @Bean
    public OpenAPI cryptobotOpenApi() {
        return new OpenAPI()
                .info(new Info().title("CryptoBot Control Plane API").version("v1")
                        .description("Wallets, proposals/intents, receipts and lineage of the Trusted Agent "
                                + "Execution control plane. /proposals, /wallets/**, /receipts/{hash}/** are "
                                + "the original surface; /intents, /executions/**, /receipts/verify are the "
                                + "additive routes of TAE phase 2 — same resources, never a rename."))
                .addSecurityItem(new SecurityRequirement().addList(BEARER))
                .schemaRequirement(BEARER, new SecurityScheme().name(BEARER).type(SecurityScheme.Type.HTTP)
                        .scheme("bearer").bearerFormat("JWT"));
    }
}
