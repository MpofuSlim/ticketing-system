package com.innbucks.userservice.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * Service-to-service projection of an organizer's (tenant's) business details,
 * keyed by {@code userUuid} (the organizer's stable cross-service identifier).
 * Returned by {@code POST /users/internal/tenants/lookup-by-uuid} and consumed
 * by event-service to attach organizer details to event responses.
 *
 * <p>Was originally keyed by {@code tenantId} (the organizer's email); flipped
 * to UUID when the email-as-tenant-id pattern was removed across the platform.
 * Field names mirror the {@code /auth/register} payload so the FE sees the same
 * vocabulary going in (registration) and coming out (event listings).
 * {@code bpoNumber} is intentionally omitted — it stays admin-only via
 * {@code /admin/users/merchants}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TenantLookupDTO {
    private UUID userUuid;
    private String businessName;
    private String businessAddress;
    private String businessEmail;
}
