package com.innbucks.userservice.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * Service-to-service projection of ONE shop-staff member's identity, returned
 * (as a list) by {@code GET /users/internal/shop-staff/by-merchant/{merchantId}/contacts}
 * and consumed by loyalty-service's earn-integrity staff registry: an earn
 * whose recipient phone belongs to a staff member of the SAME merchant is
 * refused ({@code STAFF_RECIPIENT}) — a cashier crediting a colleague's number
 * is the fraud shape the {@code SELF_EARN} check cannot see.
 *
 * <p>Deliberately trimmed to the two fields the guard consumes: the phone (the
 * match key) and the stable {@code userUuid} (so a future pair-detection
 * report can name the colleague, not just the number). No name, no email —
 * per the {@link UserContactDTO} rule that nothing beyond what the caller
 * uses crosses the S2S boundary.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(name = "StaffContact",
        description = "Minimal staff projection (userUuid + phone). Service-to-service only.")
public class StaffContactDTO {

    @Schema(example = "7c9e6679-7425-40de-944b-e07fc1f90ae7",
            description = "Stable cross-service identifier of the staff member.")
    private UUID userUuid;

    @Schema(example = "+263771234567", nullable = true,
            description = "Staff member's phone number (E.164), or null when the account has none.")
    private String phoneNumber;
}
