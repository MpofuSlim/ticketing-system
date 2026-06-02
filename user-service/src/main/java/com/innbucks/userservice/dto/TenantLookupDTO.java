package com.innbucks.userservice.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Service-to-service projection of an organizer's (tenant's) business details,
 * keyed by {@code tenantId} (the account email that other services stamp as the
 * JWT subject). Returned by {@code POST /users/internal/tenants/lookup} and
 * consumed by event-service to attach organizer details to event responses.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TenantLookupDTO {
    private String tenantId;
    private String businessName;
    private String email;
    private String address;
}
