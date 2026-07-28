package com.innbucks.userservice.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Config for the inbound Fineract Message Gateway facade
 * ({@code /fineract-gateway/**}) — the surface OUR Fineract deployment calls
 * to deliver SMS/WhatsApp through the platform's notification channels.
 *
 * <p>Fineract's {@code MESSAGE_GATEWAY} external-service row must point at
 * user-service with {@code end_point=fineract-gateway} and
 * {@code tenant_app_key} equal to {@link #tenantAppKey}. Fineract sends that
 * key as the {@code Fineract-Tenant-App-Key} header on every call; it is this
 * facade's whole authentication, so it is guarded by
 * {@link ProductionSecretsGuard} like every other S2S secret.
 */
@Data
@ConfigurationProperties(prefix = "fineract-gateway")
public class FineractGatewayProperties {

    /**
     * The only Fineract-Platform-TenantId this facade serves. A second tenant
     * would need its own key + channel credentials — reject anything else.
     */
    private String tenantId = "default";

    /** Shared secret Fineract presents as Fineract-Tenant-App-Key. */
    private String tenantAppKey;

    /**
     * Fineract campaign provider ids routed to the WhatsApp channel; every
     * other provider id (including the null a triggered send may carry) goes
     * to SMS. A campaign author picks WhatsApp by picking one of these ids.
     */
    private List<Long> whatsappProviderIds = List.of(2L);
}
