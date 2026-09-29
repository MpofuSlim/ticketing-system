package com.innbucks.userservice.devicesecurity.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Wire shapes of the call-centre / fraud-desk surface ({@code /admin/device-security}).
 * Unlike the customer surface these show everything support needs to reason
 * about a phone — reasons, references, risk features — and every device carries
 * {@code agentGuidance}: a sentence the agent can act on, so a call-centre agent
 * never has to interpret a state machine while the customer waits.
 */
public final class SupportDTOs {

    private SupportDTOs() {
    }

    @Schema(name = "SupportDevice")
    public record SupportDeviceView(
            @Schema(example = "0f8d2c3a-5b6e-4f71-8a9b-0c1d2e3f4a5b") UUID deviceId,
            @Schema(example = "+263771234512") String msisdn,
            @Schema(example = "Samsung SM-A155F · Android 15") String label,
            @Schema(example = "android") String platform,
            @Schema(example = "15") String osVersion,
            @Schema(example = "SM-A155F") String model,
            @Schema(example = "samsung") String manufacturer,
            @Schema(example = "2.4.0") String appVersion,
            @Schema(example = "TEMP_BLOCKED", description = "NEW, PENDING_PIN, TRUSTED, STEP_UP, TEMP_BLOCKED, BANNED or REVOKED") String state,
            @Schema(example = "OTP_ATTEMPTS", nullable = true, description = "Why it is in that state") String stateReason,
            @Schema(example = "2026-09-29T16:15:00+02:00", nullable = true) LocalDateTime blockedAt,
            @Schema(example = "2026-09-29T16:30:00+02:00", nullable = true) LocalDateTime blockedUntil,
            @Schema(example = "SEC-8F2KQ7", nullable = true) String supportRef,
            @Schema(example = "true") boolean ussdUnlockable,
            @Schema(example = "null", nullable = true) LocalDateTime unlockableAfter,
            @Schema(example = "null", nullable = true) LocalDateTime trustedUntil,
            @Schema(example = "2026-07-02T08:16:40+02:00", nullable = true) LocalDateTime boundAt,
            @Schema(example = "null", nullable = true) LocalDateTime coolingUntil,
            @Schema(example = "2026-07-02T08:15:52+02:00", nullable = true,
                    description = "Last time this phone verified a sign-in code. Null on a TRUSTED phone = trusted "
                            + "while DTX was only watching; it is asked for one code once codes are switched on.")
            LocalDateTime otpVerifiedAt,
            @Schema(example = "2026-07-02T08:14:03+02:00") LocalDateTime firstSeenAt,
            @Schema(example = "2026-09-29T16:14:51+02:00") LocalDateTime lastSeenAt,
            @Schema(example = "Harare", nullable = true) String lastSeenNear,
            @Schema(example = "41.221.147.12", nullable = true) String lastIp,
            @Schema(example = "null", nullable = true) LocalDateTime lastUnlockedAt,
            @Schema(nullable = true) WideBanView deviceWideBan,
            @Schema(example = "0", description = "Other accounts this physical phone is bound to") long otherAccountsOnDevice,
            @Schema(example = "Paused until 16:30 after too many wrong codes. It lifts on its own; you may unlock it now if the caller passes verification.")
            String agentGuidance) {
    }

    @Schema(name = "SupportDeviceWideBan", description = "A ban on the phone for EVERY account (SHARED_DEVICE, CONFIRMED_FRAUD).")
    public record WideBanView(
            @Schema(example = "SHARED_DEVICE") String reason,
            @Schema(example = "SEC-2B6N9R") String supportRef,
            @Schema(example = "2026-09-27T10:02:11+02:00") LocalDateTime bannedAt,
            @Schema(example = "SYSTEM") String bannedBy) {
    }

    @Schema(name = "SupportCustomerProfile")
    public record ProfileView(
            @Schema(example = "WHATSAPP", nullable = true) String preferredChannel,
            @Schema(example = "false") boolean fraudFlagged,
            @Schema(example = "null", nullable = true) LocalDateTime fraudFlaggedAt,
            @Schema(example = "null", nullable = true) String fraudFlagNote,
            @Schema(example = "2026-07-02T08:15:30+02:00", nullable = true) LocalDateTime pinIssuedAt,
            @Schema(example = "2026-09-29T11:58:14+02:00", nullable = true) LocalDateTime lastSignInAt) {
    }

    @Schema(name = "SupportCounters", description = "The numbers the OTP and *569# ceilings are counted from")
    public record CountersView(
            @Schema(example = "2", description = "Ceiling: 5 an hour") long otpChallengesLastHour,
            @Schema(example = "3", description = "Ceiling: 20 a day") long otpChallengesLastDay,
            @Schema(example = "0", description = "Ceiling: 3 a day") long ussdUnlockAttemptsLastDay,
            @Schema(example = "1") long openChallenges) {
    }

    @Schema(name = "SupportEvent")
    public record EventView(
            @Schema(example = "48213") Long id,
            @Schema(example = "2026-09-29T16:15:00+02:00") LocalDateTime occurredAt,
            @Schema(example = "DEVICE_TEMP_BLOCKED") String type,
            @Schema(example = "Phone paused (too many wrong codes)") String description,
            @Schema(example = "TEMP_BLOCKED", nullable = true) String decision,
            @Schema(example = "TEMP_BLOCKED", nullable = true, description = "Differs from decision only in watch mode")
            String evaluatedDecision,
            @Schema(example = "OTP_ATTEMPTS", nullable = true) String reason,
            @Schema(example = "SEC-8F2KQ7", nullable = true) String supportRef,
            @Schema(example = "SYSTEM") String actorType,
            @Schema(example = "null", nullable = true) String actorId,
            @Schema(example = "null", nullable = true) String channel,
            @Schema(example = "SIGN_IN", nullable = true) String purpose,
            @Schema(example = "SIGN_IN", nullable = true) String context,
            @Schema(example = "0f8d2c3a-5b6e-4f71-8a9b-0c1d2e3f4a5b", nullable = true) UUID deviceId,
            @Schema(example = "null", nullable = true) String ussdSessionId,
            @Schema(example = "41.221.147.12", nullable = true) String ipAddress,
            @Schema(example = "null", nullable = true) Integer riskScore,
            @Schema(nullable = true, description = "The features that led to the decision") Map<String, Object> features,
            @Schema(example = "null", nullable = true) String note) {
    }

    @Schema(name = "SupportCustomerOverview")
    public record CustomerOverview(
            @Schema(example = "+263771234512") String msisdn,
            ProfileView profile,
            List<SupportDeviceView> devices,
            CountersView counters,
            List<EventView> recentEvents,
            @Schema(example = "1 phone signed in; 1 phone paused until 16:30 (SEC-8F2KQ7).") String summary) {
    }

    @Schema(name = "SupportRefLookup")
    public record SupportRefLookup(
            @Schema(example = "SEC-8F2KQ7") String supportRef,
            @Schema(example = "+263771234512") String msisdn,
            @Schema(nullable = true) SupportDeviceView device,
            List<EventView> events) {
    }

    @Schema(name = "SupportPage")
    public record PageView<T>(
            List<T> content,
            @Schema(example = "1") long totalElements,
            @Schema(example = "1") int totalPages,
            @Schema(example = "0", description = "Zero-based page index.") int number,
            @Schema(example = "20") int size) {
    }

    @Schema(name = "SupportBlockRequest")
    public record BlockRequest(
            @NotBlank(message = "mode is required")
            @Pattern(regexp = "TEMPORARY|UNTIL_UNLOCKED", message = "mode must be TEMPORARY or UNTIL_UNLOCKED")
            @Schema(example = "UNTIL_UNLOCKED", requiredMode = Schema.RequiredMode.REQUIRED,
                    description = "TEMPORARY: paused for durationMinutes, lifts on its own. UNTIL_UNLOCKED: a lost/stolen "
                            + "phone — blocked until the customer unlocks it on *569# or support lifts it.")
            String mode,
            @Min(value = 15, message = "durationMinutes must be at least 15")
            @Max(value = 10080, message = "durationMinutes must be at most 10080 (7 days)")
            @Schema(example = "60", description = "TEMPORARY only.")
            Integer durationMinutes,
            @NotBlank(message = "note is required")
            @Size(max = 1000, message = "note must be 1000 characters or fewer")
            @Schema(example = "Caller reported the phone stolen at Mbare Musika; identity verified by date of birth and last transaction.",
                    requiredMode = Schema.RequiredMode.REQUIRED, description = "Why — lands on the audit chain.")
            String note) {
    }

    @Schema(name = "SupportBanRequest")
    public record BanRequest(
            @NotBlank(message = "reason is required")
            @Pattern(regexp = "FRAUD_SUSPECTED|CONFIRMED_FRAUD|SHARED_DEVICE",
                    message = "reason must be FRAUD_SUSPECTED, CONFIRMED_FRAUD or SHARED_DEVICE")
            @Schema(example = "FRAUD_SUSPECTED", requiredMode = Schema.RequiredMode.REQUIRED,
                    description = "FRAUD_SUSPECTED: this account on this phone, USSD-unlockable after 24h. CONFIRMED_FRAUD "
                            + "and SHARED_DEVICE: the phone, for EVERY account on it, support only.")
            String reason,
            @NotBlank(message = "note is required")
            @Size(max = 1000, message = "note must be 1000 characters or fewer")
            @Schema(example = "Linked to case FR-2026-0412: three mule accounts cashed out from this handset.",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            String note) {
    }

    @Schema(name = "SupportNoteRequest")
    public record NoteRequest(
            @NotBlank(message = "note is required")
            @Size(max = 1000, message = "note must be 1000 characters or fewer")
            @Schema(example = "Caller verified (DOB + last transaction). Confirmed the paused phone is theirs.",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            String note) {
    }

    @Schema(name = "SupportFraudFlagRequest")
    public record FraudFlagRequest(
            @NotNull(message = "flagged is required")
            @Schema(example = "true", requiredMode = Schema.RequiredMode.REQUIRED,
                    description = "true shortens every phone's trust to 30 days and raises the risk score; false clears it.")
            Boolean flagged,
            @NotBlank(message = "note is required")
            @Size(max = 1000, message = "note must be 1000 characters or fewer")
            @Schema(example = "Customer was the victim of a SIM-swap attempt on 2026-09-20.", requiredMode = Schema.RequiredMode.REQUIRED)
            String note) {
    }

    @Schema(name = "SupportActionResult")
    public record ActionResult(
            SupportDeviceView device,
            @Schema(example = "The customer has been sent an SMS. The next sign-in on this phone will ask for a code.")
            String whatHappensNext) {
    }
}
