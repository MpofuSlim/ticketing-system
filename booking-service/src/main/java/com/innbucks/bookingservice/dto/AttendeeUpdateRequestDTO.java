package com.innbucks.bookingservice.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Replaces one ticket's named attendee. All three fields are written as sent:
 * an omitted or blank {@code email} / {@code phoneNumber} CLEARS it, so the
 * console sends the whole attendee each time. Same bounds as
 * {@link CreateBookingRequestDTO.AttendeeRequest}.
 */
@Schema(name = "AttendeeUpdateRequest")
public record AttendeeUpdateRequestDTO(
        @Schema(example = "Joel Mandingaisa", description = "The attendee's full name, printed on the ticket.")
        @NotBlank(message = "Attendee full name is required")
        @Size(max = 120, message = "Attendee full name must be at most 120 characters")
        String fullName,

        @Schema(example = "joel@example.com", nullable = true,
                description = "Optional. Omitted or blank clears it.")
        @Email(message = "Attendee email is not a valid email address")
        @Size(max = 255, message = "Attendee email must be at most 255 characters")
        String email,

        @Schema(example = "+263713664366", nullable = true,
                description = "Optional. Full international format, or a local number for this deployment's "
                        + "country; stored in E.164. Omitted or blank clears it.")
        @Size(max = 32, message = "Attendee phone number must be at most 32 characters")
        String phoneNumber
) {
}
