package com.innbucks.eventservice.dto.scanner;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * An organizer names a person for their gate team. No email and no password:
 * the person signs in to the super app as usual and accepts there.
 */
@Schema(name = "CreateScannerRequest",
        description = "A super-app scanner: a phone number and the name the organizer knows the person by.")
public record CreateScannerRequestDTO(
        @Schema(example = "Tendai", description = "Shown in the scan log and in \"already scanned by\".")
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
        String phoneNumber
) { }
