package com.example.ecom.common.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI ecomOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("eCommerce API")
                        .description("Learning project: catalog, cart, orders and payments")
                        .version("v0.1"));
    }
}