package com.innbucks.userservice.devicesecurity.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Wire shapes of the *569# endpoints (contract §5.8, §9). Every response carries
 * {@code menuText}: the exact screen the USSD service can show, so the wording
 * the customer reads is owned (and kept neutral) in one place.
 */
public final class UssdDTOs {

    private UssdDTOs() {
    }

    @Schema(name = "UssdDevicesRequest")
    public record UssdDevicesRequest(
            @NotBlank(message = "msisdn is required")
            @Size(max = 20, message = "msisdn must be 20 characters or fewer")
            @Schema(example = "+263771234512", requiredMode = Schema.RequiredMode.REQUIRED,
                    description = "The DIALLING number, as the mobile network reports it — never a number the customer typed.")
            String msisdn,
            @Size(max = 100, message = "ussdSessionId must be 100 characters or fewer")
            @Schema(example = "ussd-20260929-0f3a9c") String ussdSessionId) {
    }

    @Schema(name = "UssdDevice")
    public record UssdDevice(
            @Schema(example = "1", description = "Menu position. Send deviceId, not the position, on the follow-up call.") int option,
            @Schema(example = "0f8d2c3a-5b6e-4f71-8a9b-0c1d2e3f4a5b") UUID deviceId,
            @Schema(example = "Samsung SM-A155F") String label,
            @Schema(example = "BANNED") String state,
            @Schema(example = "2026-09-28T19:02:44+02:00", nullable = true) LocalDateTime blockedAt,
            @Schema(example = "null", nullable = true) LocalDateTime blockedUntil,
            @Schema(example = "true", description = "Unlock only: whether *569# can lift it. false = support only.") boolean eligible,
            @Schema(example = "Samsung SM-A155F (blocked 28 Sep)") String menuLabel) {
    }

    @Schema(name = "UssdDevicesResponse")
    public record UssdDevicesResponse(
            List<UssdDevice> devices,
            @Schema(example = "Choose the phone to unlock:\n1. Samsung SM-A155F (blocked 28 Sep)") String menuText) {
    }

    @Schema(name = "UssdDeviceActionRequest")
    public record UssdDeviceActionRequest(
            @NotBlank(message = "msisdn is required")
            @Size(max = 20, message = "msisdn must be 20 characters or fewer")
            @Schema(example = "+263771234512", requiredMode = Schema.RequiredMode.REQUIRED)
            String msisdn,
            @NotNull(message = "deviceId is required")
            @Schema(example = "0f8d2c3a-5b6e-4f71-8a9b-0c1d2e3f4a5b", requiredMode = Schema.RequiredMode.REQUIRED)
            UUID deviceId,
            @NotBlank(message = "ussdSessionId is required")
            @Size(max = 100, message = "ussdSessionId must be 100 characters or fewer")
            @Schema(example = "ussd-20260929-0f3a9c", requiredMode = Schema.RequiredMode.REQUIRED,
                    description = "Recorded on the audit row (§9.3).")
            String ussdSessionId) {
    }

    @Schema(name = "UssdDeviceActionResponse")
    public record UssdDeviceActionResponse(
            @Schema(example = "UNLOCKED",
                    description = "Unlock: UNLOCKED | NOT_ELIGIBLE | TRY_LATER | NOT_FOUND. Block: BLOCKED | ALREADY_BLOCKED | TRY_LATER | NOT_FOUND.")
            String result,
            @Schema(example = "Samsung SM-A155F", nullable = true) String deviceLabel,
            @Schema(example = "SEC-8F2KQ7", nullable = true) String supportRef,
            @Schema(example = "Your phone is unlocked. Open InnBucks and sign in. We will send you a code to confirm it's you.")
            String menuText) {
    }
}
