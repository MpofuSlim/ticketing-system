package com.innbucks.bookingservice.dto;

import java.util.List;
import java.util.UUID;

/** Request body for user-service's {@code POST /users/internal/tenants/lookup-by-uuid}. */
public record TenantLookupRequest(List<UUID> userUuids) {
}
