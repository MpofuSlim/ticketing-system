package com.innbucks.userservice.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

/**
 * What {@code PUT /admin/users/{id}/reject} answers. The account no longer
 * exists when this is returned, so it names it by the {@code id} and
 * {@code email} the console showed — enough to drop the row from the pending
 * list and say whose registration was rejected.
 *
 * <p>{@code rejectedAt} holds a UTC wall clock, like every timestamp this
 * service stores; the wire renders it at the market offset for a person
 * ({@code UtcJsonTimeConfig}), so the console prints it as it arrives.
 */
@Schema(name = "RejectedRegistration", description = "A registration that was rejected and removed.")
public record RejectedRegistrationDTO(
        @Schema(example = "57", description = "The id the removed account had.")
        Long id,
        @Schema(example = "rumbi@showtime.co.zw", nullable = true, description = "The email the removed account had.")
        String email,
        @Schema(example = "We couldn't verify the BPO number you gave. Please register again with the number on "
                + "your ZIMRA certificate.", description = "The reason sent to the applicant.")
        String reason,
        @Schema(example = "2026-10-08T14:30:00+02:00", description = "When the registration was rejected.")
        LocalDateTime rejectedAt) {
}
