package com.innbucks.userservice.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * Service-to-service projection mapping a user's account email (which is
 * the JWT subject and the legacy {@code events.tenant_id} value) to the
 * stable {@code user_uuid}. Returned by
 * {@code POST /users/internal/users/by-email} and consumed by
 * event-service's tenant-user-uuid backfill runner.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EmailToUserUuidDTO {
    private String email;
    private UUID userUuid;
}
