package com.innbucks.userservice.devicesecurity.dto;

import com.innbucks.userservice.devicesecurity.OtpChannel;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/** Wire shapes of Profile › Security › Your devices (§5.6) and the in-session step-up (§5.5). */
public final class CustomerDeviceDTOs {

    private CustomerDeviceDTOs() {
    }

    @Schema(name = "CustomerDevice")
    public record CustomerDeviceView(
            @Schema(example = "0f8d2c3a-5b6e-4f71-8a9b-0c1d2e3f4a5b") UUID deviceId,
            @Schema(example = "Samsung SM-A155F · Android 15") String label,
            @Schema(example = "2026-07-02T08:14:03+02:00") LocalDateTime firstSeenAt,
            @Schema(example = "2026-09-29T11:58:12+02:00") LocalDateTime lastSeenAt,
            @Schema(example = "Harare", nullable = true, description = "Town name only, never coordinates.") String lastSeenNear,
            @Schema(example = "true", description = "The phone making this request.") boolean current,
            @Schema(example = "TRUSTED") String state,
            @Schema(example = "Signed in", description = "A plain-words status to print next to the device.") String status,
            @Schema(example = "2026-12-28T11:58:12+02:00", nullable = true) LocalDateTime trustedUntil) {
    }

    @Schema(name = "CustomerDevicesResponse")
    public record CustomerDevicesResponse(
            List<CustomerDeviceView> devices,
            @Schema(example = "false", description = "true = more trusted phones than allowed; ask the customer to remove old ones.")
            boolean tidyUpSuggested) {
    }

    @Schema(name = "SessionStepUpRequest")
    public record StepUpRequest(
            @NotBlank(message = "action is required")
            @Pattern(regexp = "LARGE_TRANSFER|NEW_PAYEE|PIN_CHANGE|CASH_OUT|OTHER",
                    message = "action must be LARGE_TRANSFER, NEW_PAYEE, PIN_CHANGE, CASH_OUT or OTHER")
            @Schema(example = "LARGE_TRANSFER", requiredMode = Schema.RequiredMode.REQUIRED,
                    description = "What the customer is about to do. Stamped into the proof.")
            String action,
            @Schema(example = "false", description = "true = ask for a code even on a long-trusted phone.")
            Boolean force) {
    }

    @Schema(name = "SessionStepUpResponse")
    public record StepUpResponse(
            @Schema(example = "OTP_REQUIRED", allowableValues = {"NOT_REQUIRED", "OTP_REQUIRED"}) String decision,
            @Schema(example = "chl_7k2m9q4t8v1x3z5b6c0d2f4g6h", nullable = true) String challengeId,
            @Schema(example = "[\"WHATSAPP\", \"SMS\"]", nullable = true) List<OtpChannel> channels,
            @Schema(example = "WHATSAPP", nullable = true) OtpChannel defaultChannel,
            @Schema(example = "+263 77 *** **12", nullable = true) String destinationMasked) {
    }

    @Schema(name = "SessionStepUpVerifyRequest")
    public record StepUpVerifyRequest(
            @NotBlank(message = "challengeId is required")
            @Size(max = 40, message = "challengeId is not valid")
            @Schema(example = "chl_7k2m9q4t8v1x3z5b6c0d2f4g6h", requiredMode = Schema.RequiredMode.REQUIRED)
            String challengeId,
            @NotBlank(message = "otp is required")
            @Pattern(regexp = "\\d{6}", message = "otp must be the 6-digit code")
            @Schema(example = "482913", requiredMode = Schema.RequiredMode.REQUIRED)
            String otp) {
    }

    @Schema(name = "SessionStepUpVerifyResponse")
    public record StepUpVerifyResponse(
            @Schema(example = "true") boolean verified,
            @Schema(example = "LARGE_TRANSFER") String action,
            @Schema(example = "eyJhbGciOiJSUzI1NiJ9.eyJ0eXAiOiJzdGVwX3VwIn0.sig",
                    description = "RS256, 5 minutes: proof that this number re-proved possession on this phone.")
            String stepUpProof,
            @Schema(example = "2026-09-29T12:05:00+02:00") LocalDateTime expiresAt) {
    }
}
