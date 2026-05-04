package com.innbucks.loyaltyservice.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {
    @Bean
    public OpenAPI openAPI() {
        return new OpenAPI().info(new Info()
                .title("Loyalty & Voucher Management Platform")
                .description("Multi-tenant LVMP: tenants, merchants, wallets, points, vouchers, invoicing, QR, reporting")
                .version("1.0.0"));
    }
}
