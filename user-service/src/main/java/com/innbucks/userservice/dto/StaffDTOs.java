package com.innbucks.userservice.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.innbucks.userservice.util.TrimToNullDeserializer;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/** Request and response shapes for staff accounts ({@code /admin/staff}, {@code /auth/staff-invite}, V44). */
public final class StaffDTOs {

    private StaffDTOs() {}

    /** The registration name rule: letters of any script, spaces, hyphens, apostrophes; starts with a letter. */
    static final String NAME_REGEX = "^\\p{L}[\\p{L} '\\-]*$";

    @Schema(name = "StaffCreateRequest",
            description = "Creates a staff account and emails the person a single-use invite to set a password. "
                    + "There is no password field.")
    public record CreateRequest(
            @JsonDeserialize(using = TrimToNullDeserializer.class)
            @NotBlank(message = "First name is required")
            @Size(max = 50, message = "First name must not exceed 50 characters")
            @Pattern(regexp = NAME_REGEX, message = "First name may contain only letters, spaces, hyphens and apostrophes")
            @Schema(example = "Tariro", requiredMode = Schema.RequiredMode.REQUIRED)
            String firstName,

            @JsonDeserialize(using = TrimToNullDeserializer.class)
            @NotBlank(message = "Last name is required")
            @Size(max = 50, message = "Last name must not exceed 50 characters")
            @Pattern(regexp = NAME_REGEX, message = "Last name may contain only letters, spaces, hyphens and apostrophes")
            @Schema(example = "Moyo", requiredMode = Schema.RequiredMode.REQUIRED)
            String lastName,

            @NotBlank(message = "Email is required")
            @Size(max = 254, message = "Email must not exceed 254 characters")
            @Email(message = "Invalid email")
            @Schema(example = "tariro.moyo@innbucks.co.zw", requiredMode = Schema.RequiredMode.REQUIRED,
                    description = "The person's OWN InnBucks address, on a staff domain. No +tag, no shared "
                            + "mailbox (support@, info@ …). Stored lower-cased.")
            String email,

            @JsonDeserialize(using = TrimToNullDeserializer.class)
            @Size(max = 20, message = "Phone number must not exceed 20 characters")
            @Schema(example = "+263771234567", nullable = true,
                    description = "Optional CONTACT number, any country. Never a sign-in identifier: staff sign in "
                            + "with their email only and cannot reset a password by phone.")
            String phoneNumber,

            @JsonDeserialize(using = TrimToNullDeserializer.class)
            @NotBlank(message = "Country is required")
            @Size(max = 100, message = "Country must not exceed 100 characters")
            @Schema(example = "Zimbabwe", requiredMode = Schema.RequiredMode.REQUIRED)
            String country,

            @NotEmpty(message = "At least one role is required")
            @Size(max = 10, message = "At most 10 roles")
            @Schema(example = "[\"CALL_CENTER_AGENT\"]", requiredMode = Schema.RequiredMode.REQUIRED,
                    description = "Staff roles only (PRODUCT_*, CALL_CENTER_*, FRAUD_DESK, or a custom role holding "
                            + "a platform permission), each within your own authority. Never SUPER_ADMIN.")
            List<@NotBlank(message = "Role names must be non-blank") String> roles,

            @NotBlank(message = "note is required")
            @Size(max = 1000, message = "note must not exceed 1000 characters")
            @Schema(example = "Joins the Harare call-center team on 1 Oct (HR-2291).",
                    requiredMode = Schema.RequiredMode.REQUIRED,
                    description = "Why this account exists. Recorded on the audit trail.")
            String note) {
    }

    @Schema(name = "StaffNoteRequest")
    public record NoteRequest(
            @NotBlank(message = "note is required")
            @Size(max = 1000, message = "note must not exceed 1000 characters")
            @Schema(example = "Left the company on 30 Sept.", requiredMode = Schema.RequiredMode.REQUIRED)
            String note) {
    }

    @Schema(name = "StaffCreatedBy")
    public record CreatedBy(@Schema(example = "admin@innbucks.co.zw") String email) {
    }

    @Schema(name = "StaffInviteState", description = "Present only while the account is INVITED.")
    public record InviteState(
            @Schema(example = "tariro.moyo@innbucks.co.zw") String sentTo,
            @Schema(example = "2026-10-02T10:15:02+02:00") LocalDateTime expiresAt,
            @Schema(example = "PENDING", description = "PENDING (being sent), SENT, or FAILED — show FAILED as "
                    + "\"We couldn't send the invite. Check the address and resend.\"") String deliveryStatus) {
    }

    @Schema(name = "StaffAccount")
    public record StaffView(
            @Schema(example = "4812") Long id,
            @Schema(example = "8f3c2b1e-6a4d-4e0f-9b7a-2d5c1e9f0a11") UUID userUuid,
            @Schema(example = "Tariro") String firstName,
            @Schema(example = "Moyo") String lastName,
            @Schema(example = "tariro.moyo@innbucks.co.zw") String email,
            @Schema(example = "+263771234567", nullable = true,
                    description = "The CONTACT number (never a sign-in identifier). For a legacy account not yet "
                            + "adopted, its sign-in phone.") String phoneNumber,
            @Schema(example = "Zimbabwe") String country,
            @Schema(example = "[\"CALL_CENTER_AGENT\"]") List<String> roles,
            @Schema(example = "[\"device-security:manage\", \"device-security:read\"]",
                    description = "What the roles resolve to, sorted.") List<String> permissions,
            @Schema(example = "INVITED", description = "INVITED, ACTIVE or DEACTIVATED.") String status,
            @Schema(example = "false", description = "True once an invite was redeemed.") boolean emailVerified,
            @Schema(example = "true", description = "The email passes the staff-address rule under current config.")
            boolean emailDomainAllowed,
            @Schema(example = "false") boolean mfaEnrolled,
            @Schema(example = "false") boolean lockedOut,
            @Schema(example = "2026-09-30T08:41:10+02:00", nullable = true) LocalDateTime lastSignInAt,
            @Schema(example = "2026-09-29T10:15:02+02:00") LocalDateTime createdAt,
            @JsonInclude(JsonInclude.Include.NON_NULL) CreatedBy createdBy,
            @Schema(example = "true", description = "Whether YOU may deactivate, reactivate or re-invite it.")
            boolean manageable,
            @JsonInclude(JsonInclude.Include.NON_NULL) InviteState invite,
            @JsonInclude(JsonInclude.Include.NON_NULL)
            @Schema(nullable = true, description = "Legacy rows only (no staff profile): may it be adopted with "
                    + "POST /admin/staff/{id}/resend-invite?") Boolean adoptable,
            @JsonInclude(JsonInclude.Include.NON_NULL)
            @Schema(nullable = true, example = "organization_member",
                    description = "Legacy rows only: holds_non_staff_roles, organization_member or off_domain.")
            String adoptionBlockedReason,
            @JsonInclude(JsonInclude.Include.NON_NULL)
            @Schema(nullable = true, description = "Server-rendered next step, on create and lifecycle responses.")
            String whatHappensNext) {

        public StaffView withNext(String next) {
            return new StaffView(id, userUuid, firstName, lastName, email, phoneNumber, country, roles, permissions,
                    status, emailVerified, emailDomainAllowed, mfaEnrolled, lockedOut, lastSignInAt, createdAt,
                    createdBy, manageable, invite, adoptable, adoptionBlockedReason, next);
        }
    }

    @Schema(name = "StaffPage")
    public record Page<T>(
            List<T> content,
            @Schema(example = "1") long totalElements,
            @Schema(example = "1") int totalPages,
            @Schema(example = "0", description = "Zero-based page index.") int number,
            @Schema(example = "20") int size) {
    }

    @Schema(name = "StaffAuditEntry")
    public record AuditEntry(
            @Schema(example = "2026-09-29T10:15:02+02:00") LocalDateTime at,
            @Schema(example = "STAFF_INVITED") String type,
            @Schema(example = "SUCCESS") String outcome,
            @Schema(example = "admin@innbucks.co.zw") String actor,
            @Schema(example = "Created with role CALL_CENTER_AGENT and invited by email.") String summary,
            @JsonInclude(JsonInclude.Include.NON_NULL)
            @Schema(example = "Joins the Harare call-center team on 1 Oct (HR-2291).", nullable = true) String note) {
    }

    @Schema(name = "StaffInviteTokenRequest")
    public record InviteTokenRequest(
            @NotBlank(message = "token is required")
            @Size(max = 128, message = "token is invalid")
            @Schema(example = "STI-Q2hlY2tpbmcgdGhhdCB0aGlzIGlzIG5vdCBhIHJlYWwgdG9rZW4x",
                    requiredMode = Schema.RequiredMode.REQUIRED,
                    description = "The token from the invite link's fragment (#token=…).")
            String token) {

        /** Never print the token. */
        @Override
        public String toString() {
            return "InviteTokenRequest[<redacted>]";
        }
    }

    @Schema(name = "StaffInviteAcceptRequest")
    public record AcceptRequest(
            @NotBlank(message = "token is required")
            @Size(max = 128, message = "token is invalid")
            @Schema(example = "STI-Q2hlY2tpbmcgdGhhdCB0aGlzIGlzIG5vdCBhIHJlYWwgdG9rZW4x",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            String token,
            @NotBlank(message = "New password is required")
            @Size(min = 8, max = 72, message = "Password must be between 8 and 72 characters")
            @Schema(example = "Correct-Horse-7-Battery", requiredMode = Schema.RequiredMode.REQUIRED)
            String newPassword,
            @NotBlank(message = "Please confirm the new password")
            @Schema(example = "Correct-Horse-7-Battery", requiredMode = Schema.RequiredMode.REQUIRED)
            String confirmPassword) {

        /** Never print the token or the passwords. */
        @Override
        public String toString() {
            return "AcceptRequest[<redacted>]";
        }
    }

    @Schema(name = "StaffInviteInspection")
    public record InviteInspection(
            @Schema(example = "Tariro") String firstName,
            @Schema(example = "tariro.moyo@innbucks.co.zw") String email,
            @Schema(example = "2026-10-02T10:15:02+02:00") LocalDateTime expiresAt) {
    }

    @Schema(name = "StaffInviteAccepted")
    public record InviteAccepted(@Schema(example = "tariro.moyo@innbucks.co.zw") String email) {
    }
}
