package com.innbucks.loyaltyservice.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.parameters.HeaderParameter;
import io.swagger.v3.oas.models.media.StringSchema;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI openAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Loyalty & Voucher Management Platform")
                        .version("1.0.0")
                        .description("""
                                Multi-tenant Loyalty & Voucher Management Platform (LVMP).

                                **Core domains** (each grouped under its own tag below):
                                - Tenants — top-level platform tenants
                                - Merchants — branded brands/outlets that issue points & vouchers
                                - Rules & Campaigns — earn-rate rules and time-bound multipliers
                                - Transactions — earn / redeem / adjust / reverse / transfer points
                                - Vouchers — templates, issuance, redemption, anti-fraud
                                - QR — signed merchant earn / P2P transfer tokens
                                - Invoicing — periodic merchant billing
                                - Reporting — operator/tenant/merchant/user dashboards
                                - Mini-apps — SuperApp shell manifest

                                **Tenant header (required on every tenant-scoped endpoint):**
                                send either `X-Tenant-Id: <uuid>` OR `X-Tenant-Code: <slug>`. The only
                                endpoints that do NOT require a tenant header are
                                `POST /api/loyalty/tenants` and `GET /api/loyalty/tenants` (operator-level).

                                **Identity boundary:** loyalty-service does NOT own user identity.
                                Customers register and are stored in user-service. This service only
                                holds a per-tenant projection (LoyaltyUser) keyed by phone number.
                                Wallets, transactions, and vouchers all reference that projection's
                                internal UUID, NOT the user-service userId directly.
                                """))
                .components(new Components()
                        .addParameters("X-Tenant-Id",
                                new HeaderParameter()
                                        .name("X-Tenant-Id")
                                        .description("Tenant UUID — required on every tenant-scoped endpoint (alternative to X-Tenant-Code).")
                                        .required(false)
                                        .schema(new StringSchema().format("uuid")))
                        .addParameters("X-Tenant-Code",
                                new HeaderParameter()
                                        .name("X-Tenant-Code")
                                        .description("Tenant short code — required on every tenant-scoped endpoint (alternative to X-Tenant-Id).")
                                        .required(false)
                                        .schema(new StringSchema())));
    }
}
