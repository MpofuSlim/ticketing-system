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
 *
 * <p>Field names mirror the {@code /auth/register} payload so the FE sees the
 * same vocabulary going in (registration) and coming out (event listings).
 * {@code bpoNumber} is intentionally omitted — it stays admin-only via
 * {@code /admin/users/merchants}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TenantLookupDTO {
    private String tenantId;
    private String businessName;
    private String businessAddress;
    private String businessEmail;
}
