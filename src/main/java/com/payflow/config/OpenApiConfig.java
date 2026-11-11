package com.payflow.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

  @Bean
  OpenAPI payflowOpenApi() {
    return new OpenAPI()
        .info(
            new Info()
                .title("PayFlow API")
                .version("1.0.0")
                .description(
                    "Idempotent payments with a double-entry ledger. Every mutating endpoint requires"
                        + " an Idempotency-Key header and an X-Merchant-Id header. Errors are RFC 9457"
                        + " problem+json with a stable `code`."));
  }
}
