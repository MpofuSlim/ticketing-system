package com.innbucks.userservice.devicesecurity.dto;

import com.innbucks.userservice.devicesecurity.Decision;
import com.innbucks.userservice.devicesecurity.OtpChannel;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Wire shapes of the app-facing DTX endpoints (contract §5.1–§5.3, §6, §7). Field
 * names are the contract's. Enumerated values are validated as strings so a bad
 * value is a 400 that NAMES the field (§7.7), not an unreadable-body error.
 */
public final class DeviceSignInDTOs {

    private DeviceSignInDTOs() {
    }

    // ---- POST /auth/client-service ------------------------------------------------

    @Schema(name = "DtxClientServiceRequest",
            description = "Starts every sign-in and every silent renewal (§5.1, §6). No PIN, ever.")
    public record ClientServiceRequest(
            @NotBlank(message = "requestId is required")
            @Size(max = 64, message = "requestId must be 64 characters or fewer")
            @Schema(example = "3f4c2a9e-7d1b-4c55-9a0e-2b8f6d1c4e77", requiredMode = Schema.RequiredMode.REQUIRED,
                    description = "UUID per attempt. A retry with the same value reuses the same OTP challenge.")
            String requestId,

            @Schema(example = "2026-09-29T09:58:12.431Z", description = "When the app sent it. Logged, not trusted.")
            String sentAt,

            @Pattern(regexp = "SIGN_IN|PIN_ISSUE|LOOKUP", message = "purpose must be SIGN_IN, PIN_ISSUE or LOOKUP")
            @Schema(example = "SIGN_IN", allowableValues = {"SIGN_IN", "PIN_ISSUE", "LOOKUP"},
                    description = "What the token is for. Defaults to SIGN_IN.")
            String purpose,

            @Pattern(regexp = "SIGN_IN|RENEW", message = "context must be SIGN_IN or RENEW")
            @Schema(example = "SIGN_IN", allowableValues = {"SIGN_IN", "RENEW"},
                    description = "SIGN_IN when the customer is signing in; RENEW for the silent ~15-minute renewal.")
            String context,

            @NotBlank(message = "msisdn is required")
            @Size(max = 20, message = "msisdn must be 20 characters or fewer")
            @Schema(example = "+263771234512", requiredMode = Schema.RequiredMode.REQUIRED,
                    description = "The customer's number, E.164. Local spellings (0771234512) are accepted and normalised.")
            String msisdn,

            @NotNull(message = "device is required")
            @Valid
            DeviceFacts device,

            @Valid
            Integrity integrity,

            @Valid
            Location location,

            Consent consent) {
    }

    @Schema(name = "DtxDeviceFacts", description = "What the phone reports about itself (§6.1).")
    public record DeviceFacts(
            @NotBlank(message = "device.installId is required")
            @Size(max = 64, message = "device.installId must be 64 characters or fewer")
            @Schema(example = "8b1e6c0e-4f2a-4d8e-9b3c-1a2b3c4d5e6f", requiredMode = Schema.RequiredMode.REQUIRED,
                    description = "The x-device-id the app already sends. Must equal the x-device-id header when both are present.")
            String installId,
            @Size(max = 16) @Schema(example = "android") String platform,
            @Size(max = 32) @Schema(example = "15") String osVersion,
            @Size(max = 64) @Schema(example = "SM-A155F") String model,
            @Size(max = 64) @Schema(example = "samsung") String manufacturer,
            @Size(max = 32) @Schema(example = "2.4.0") String appVersion,
            @Size(max = 32) @Schema(example = "2.2.0") String runtimeVersion,
            @Size(max = 64) @Schema(example = "a5ca7916-2f1e-4b0a-9c3d-5e6f7a8b9c0d") String updateId,
            @Size(max = 16) @Schema(example = "en-ZW") String locale,
            @Size(max = 64) @Schema(example = "Africa/Harare") String timezone,
            Screen screen,
            Biometrics biometrics) {
    }

    @Schema(name = "DtxScreen")
    public record Screen(Integer w, Integer h, Double scale) {
    }

    @Schema(name = "DtxBiometrics")
    public record Biometrics(Boolean available, Boolean enrolled, List<String> types) {
    }

    @Schema(name = "DtxIntegrity", description = "freeRASP findings (§6.1).")
    public record Integrity(
            @Size(max = 32) @Schema(example = "freerasp", description = "freerasp in store builds; unavailable in developer runtimes.")
            String source,
            @Schema(example = "true") Boolean safeForPayments,
            @Size(max = 20, message = "integrity.threats has too many entries")
            @Schema(example = "[]", description = "freeRASP callback names, e.g. privilegedAccess, hooks, appIntegrity, "
                    + "automation, malware, simulator, debug. unofficialStore is ignored.")
            List<String> threats) {
    }

    @Schema(name = "DtxLocation", description = "Taken once per sign-in, rounded to 3 decimals (§3 rule 8).")
    public record Location(
            @Pattern(regexp = "GRANTED|DENIED|UNAVAILABLE|TIMEOUT",
                    message = "location.status must be GRANTED, DENIED, UNAVAILABLE or TIMEOUT")
            @Schema(example = "GRANTED") String status,
            @DecimalMin(value = "-90.0", message = "location.lat is out of range")
            @DecimalMax(value = "90.0", message = "location.lat is out of range")
            @Schema(example = "-17.825") Double lat,
            @DecimalMin(value = "-180.0", message = "location.lng is out of range")
            @DecimalMax(value = "180.0", message = "location.lng is out of range")
            @Schema(example = "31.053") Double lng,
            @Schema(example = "35") Double accuracyM,
            @Schema(example = "1200") Long ageMs,
            @Schema(example = "false") Boolean mocked) {
    }

    @Schema(name = "DtxConsent")
    public record Consent(Boolean locationForSecurity, String policyVersion) {
    }

    // ---- The four decisions (§7) ---------------------------------------------------

    /** One of the four decision bodies. {@code decision} is the only field the app branches on (§7.1). */
    public sealed interface SignInDecision permits TokenDecision, OtpRequiredDecision, TempBlockedDecision, BannedDecision {
        Decision decision();
    }

    @Schema(name = "DtxTokenDecision", description = "decision=TOKEN (§7.2).")
    public record TokenDecision(
            @Schema(example = "TOKEN") Decision decision,
            ClientServiceTokenView clientService,
            @Schema(example = "eyJhbGciOiJSUzI1NiIsImtpZCI6ImR0eC10aWNrZXQtMSJ9.eyJzdWIiOiIrMjYzNzcxMjM0NTEyIn0.sig",
                    description = "RS256, 2 minutes, single use. Send it as x-dtx-ticket on the user login.")
            String loginTicket,
            TrustView trust,
            LimitsView limits) implements SignInDecision {
    }

    @Schema(name = "DtxClientServiceToken")
    public record ClientServiceTokenView(
            @Schema(example = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJjbGllbnQtc2VydmljZSJ9.sig") String accessToken,
            @Schema(example = "2026-09-29T12:00:12+02:00") LocalDateTime expiresAt) {
    }

    @Schema(name = "DtxTrust")
    public record TrustView(
            @Schema(example = "0f8d2c3a-5b6e-4f71-8a9b-0c1d2e3f4a5b",
                    description = "DTX's id for this phone on this account. Not the install id.") UUID deviceId,
            @Schema(example = "PENDING_PIN") String state,
            @Schema(example = "2026-12-28T11:58:12+02:00") LocalDateTime trustedUntil,
            @Schema(example = "true") boolean newDevice) {
    }

    @Schema(name = "DtxLimits")
    public record LimitsView(
            @Schema(example = "false", description = "Always false for now: no reduced new-phone limits are enforced yet, "
                    + "so show no limits notice. Kept so the shape does not change when they are.") boolean cooling,
            @Schema(example = "null", nullable = true, description = "Always null while cooling is false.")
            LocalDateTime coolingUntil) {
    }

    @Schema(name = "DtxOtpRequiredDecision", description = "decision=OTP_REQUIRED (§7.3).")
    public record OtpRequiredDecision(
            @Schema(example = "OTP_REQUIRED") Decision decision,
            @Schema(example = "chl_7k2m9q4t8v1x3z5b6c0d2f4g6h") String challengeId,
            @Schema(example = "[\"WHATSAPP\", \"SMS\"]") List<OtpChannel> channels,
            @Schema(example = "WHATSAPP") OtpChannel defaultChannel,
            @Schema(example = "+263 77 *** **12") String destinationMasked,
            @Schema(example = "NEW_DEVICE", description = "For logs only. Show one neutral line for every value.") String reason)
            implements SignInDecision {
    }

    @Schema(name = "DtxTempBlockedDecision", description = "decision=TEMP_BLOCKED (§7.4).")
    public record TempBlockedDecision(
            @Schema(example = "TEMP_BLOCKED") Decision decision,
            @Schema(example = "2026-09-29T16:30:00+02:00", nullable = true,
                    description = "null while an integrity hold lasts") LocalDateTime blockedUntil,
            @Schema(example = "OTP_ATTEMPTS", description = "For logs only.") String reason,
            @Schema(example = "SEC-8F2KQ7") String supportRef) implements SignInDecision {
    }

    @Schema(name = "DtxBannedDecision", description = "decision=BANNED (§7.5).")
    public record BannedDecision(
            @Schema(example = "BANNED") Decision decision,
            @Schema(example = "FRAUD_SUSPECTED", description = "For logs only.") String reason,
            @Schema(example = "true", description = "true = show the *569# button; false = show the support line.") boolean ussdUnlock,
            @Schema(example = "*569#") String ussdCode,
            @Schema(example = "+263 8677 000 000", nullable = true) String supportPhone,
            @Schema(example = "SEC-8F2KQ7") String supportRef) implements SignInDecision {
    }

    // ---- POST /auth/client-service/otp/send and /otp/verify ------------------------

    @Schema(name = "DtxOtpSendRequest")
    public record OtpSendRequest(
            @NotBlank(message = "challengeId is required")
            @Size(max = 40, message = "challengeId is not valid")
            @Schema(example = "chl_7k2m9q4t8v1x3z5b6c0d2f4g6h", requiredMode = Schema.RequiredMode.REQUIRED)
            String challengeId,
            @NotBlank(message = "channel is required")
            @Pattern(regexp = "WHATSAPP|SMS", message = "channel must be WHATSAPP or SMS")
            @Schema(example = "WHATSAPP", allowableValues = {"WHATSAPP", "SMS"}, requiredMode = Schema.RequiredMode.REQUIRED)
            String channel) {
    }

    @Schema(name = "DtxOtpSendResponse")
    public record OtpSendResponse(
            @Schema(example = "chl_7k2m9q4t8v1x3z5b6c0d2f4g6h") String challengeId,
            @Schema(example = "WHATSAPP") OtpChannel channel,
            @Schema(example = "+263 77 *** **12") String destinationMasked,
            @Schema(example = "2026-09-29T12:05:00+02:00") LocalDateTime expiresAt,
            @Schema(example = "2026-09-29T12:01:00+02:00") LocalDateTime resendAfter,
            @Schema(example = "3") int attemptsLeft,
            @Schema(example = "2") int resendsLeft) {
    }

    @Schema(name = "DtxOtpVerifyRequest")
    public record OtpVerifyRequest(
            @NotBlank(message = "challengeId is required")
            @Size(max = 40, message = "challengeId is not valid")
            @Schema(example = "chl_7k2m9q4t8v1x3z5b6c0d2f4g6h", requiredMode = Schema.RequiredMode.REQUIRED)
            String challengeId,
            @NotBlank(message = "otp is required")
            @Pattern(regexp = "\\d{6}", message = "otp must be the 6-digit code")
            @Schema(example = "482913", requiredMode = Schema.RequiredMode.REQUIRED)
            String otp) {
    }
}
