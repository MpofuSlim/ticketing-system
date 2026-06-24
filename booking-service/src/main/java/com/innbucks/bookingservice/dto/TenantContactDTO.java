package com.innbucks.bookingservice.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.UUID;

/**
 * One organizer's business contact, as returned by user-service's
 * {@code POST /users/internal/tenants/lookup-by-uuid}. Only {@code businessEmail}
 * is consumed here (to deliver commission invoices); the rest is ignored.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TenantContactDTO(
        UUID userUuid,
        String businessName,
        String businessAddress,
        String businessEmail
) {
}
