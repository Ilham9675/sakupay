package com.sakupay.common.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Title and description shown in Swagger UI ({@code /swagger-ui.html}). */
@Configuration
public class OpenApiConfig {

    @Bean
    OpenAPI sakuPayOpenApi() {
        return new OpenAPI().info(new Info()
                .title("SakuPay API")
                .version("0.1.0")
                .description("Digital wallet backend: accounts, double-entry ledger, idempotent transfers, "
                        + "top-up webhooks, fraud rules and an audit trail.")
                .license(new License().name("MIT").url("https://opensource.org/licenses/MIT")));
    }
}
