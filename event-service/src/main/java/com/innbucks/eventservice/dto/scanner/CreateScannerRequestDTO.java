package com.innbucks.eventservice.dto.scanner;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * An organizer names a person for their event team. No email and no password:
 * the person signs in to the super app as usual and accepts there. They scan,
 * sell (a sales agent, V21), or both.
 */
@Schema(name = "CreateScannerRequest",
        description = "A team member: a phone number, the name the organizer knows the person by, and what they do.")
public record CreateScannerRequestDTO(
        @Schema(example = "Tendai", description = "Shown in the scan log, \"already scanned by\" and sales reports.")
        @NotBlank(message = "First name is required")
        @Size(max = 100, message = "First name must be at most 100 characters")
        String firstName,

        @Schema(example = "Moyo")
        @NotBlank(message = "Last name is required")
        @Size(max = 100, message = "Last name must be at most 100 characters")
        String lastName,

        @Schema(example = "0771234567",
                description = "Any common spelling (0771234567, 263771234567, +263771234567); stored as E.164.")
        @NotBlank(message = "Phone number is required")
        @Size(max = 32, message = "Phone number must be at most 32 characters")
        String phoneNumber,

        @Schema(example = "true", nullable = true, defaultValue = "true",
                description = "Scans tickets at the gate. Absent = true. At least one of canScan and canSell "
                        + "must be true.")
        Boolean canScan,

        @Schema(example = "false", nullable = true, defaultValue = "false",
                description = "Sells tickets for you (a sales agent). Absent = false.")
        Boolean canSell
) {
    /** A scan-only team member, as every one was before sales agents. */
    public CreateScannerRequestDTO(String firstName, String lastName, String phoneNumber) {
        this(firstName, lastName, phoneNumber, null, null);
    }
}
