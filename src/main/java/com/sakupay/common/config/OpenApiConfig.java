package com.sakupay.common.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Title, description and the bearer-token scheme shown in Swagger UI ({@code /swagger-ui.html}):
 * log in, press "Authorize" and paste the access token.
 */
@Configuration
public class OpenApiConfig {

    private static final String BEARER = "bearer-jwt";

    @Bean
    OpenAPI sakuPayOpenApi() {
        return new OpenAPI().info(new Info()
                .title("SakuPay API")
                .version("0.1.0")
                .description("Digital wallet backend: accounts, double-entry ledger, idempotent transfers, "
                        + "top-up webhooks, fraud rules and an audit trail.")
                .license(new License().name("MIT").url("https://opensource.org/licenses/MIT")))
                .components(new Components().addSecuritySchemes(BEARER, new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER));
    }
}
