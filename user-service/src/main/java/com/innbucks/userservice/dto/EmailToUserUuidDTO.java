package com.innbucks.userservice.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * Service-to-service projection mapping a user's account email (the legacy
 * {@code events.tenant_id} value) to the stable {@code user_uuid}. Returned
 * by {@code POST /users/internal/users/by-email}.
 *
 * <p>Only exists to support event-service's
 * {@code TenantUserUuidBackfillRunner} while it migrates pre-V6 rows whose
 * {@code tenant_user_uuid} is still null. The follow-up release drops this
 * DTO, the endpoint, and the {@code events.tenant_id} column together.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EmailToUserUuidDTO {
    private String email;
    private UUID userUuid;
}
