package com.innbucks.userservice.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * Service-to-service projection of a user's contact details, keyed by
 * {@code userUuid} (the stable cross-service identifier). Returned by
 * {@code GET /users/internal/{userUuid}/contact} and consumed by
 * loyalty-service to notify a user that they've been attached to a tenant
 * (loyalty only holds the user's UUID, so it resolves the phone/email here).
 *
 * <p>Deliberately trimmed to only the fields the caller consumes — the
 * recipient's phone (primary WhatsApp/SMS target), email, and first name (for
 * a personalised greeting). No PII beyond that leaves the S2S boundary.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(name = "UserContact",
        description = "Minimal contact projection of a user, keyed by user_uuid. Service-to-service only.")
public class UserContactDTO {

    @Schema(example = "7c9e6679-7425-40de-944b-e07fc1f90ae7",
            description = "Stable cross-service identifier of the user.")
    private UUID userUuid;

    @Schema(example = "+263771234567",
            description = "User's phone number (E.164). Primary WhatsApp/SMS notification target.")
    private String phoneNumber;

    @Schema(example = "alice@example.com", nullable = true,
            description = "User's email, if set.")
    private String email;

    @Schema(example = "Alice", description = "User's first name, for a personalised greeting.")
    private String firstName;
}
