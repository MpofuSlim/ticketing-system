package com.innbucks.userservice.support.dto;

import com.innbucks.userservice.devicesecurity.dto.SupportDTOs.CountersView;
import com.innbucks.userservice.devicesecurity.dto.SupportDTOs.EventView;
import com.innbucks.userservice.devicesecurity.dto.SupportDTOs.ProfileView;
import com.innbucks.userservice.devicesecurity.dto.SupportDTOs.SupportDeviceView;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Wire shapes of unified customer support ({@code /admin/support/**}).
 *
 * <p>Every shape here is an ALLOW-LIST: a field reaches the agent only because
 * it is named below. Never add a field by embedding an entity or a DTO built for
 * another audience — that is how a secret (a password hash, a TOTP secret, an
 * install id, an OTP) leaks, one refactor later. {@code SupportResponseAllowListTest}
 * pins the component names.
 *
 * <p>Timestamps are UTC {@code LocalDateTime}s, rendered at the market offset by
 * {@code UtcJsonTimeConfig} (an {@code /admin} surface is human-facing).
 */
public final class SupportDTOs {

    private SupportDTOs() {
    }

    // ---- search ----------------------------------------------------------------------------

    @Schema(name = "SupportSearchRequest", description = "The query rides in the BODY — never a URL, so customer "
            + "PII stays out of nginx, gateway and Cloudflare logs and out of browser history.")
    public record SearchRequest(
            @NotBlank(message = "q is required")
            @Size(max = 254, message = "q must be 254 characters or fewer")
            @Schema(example = "+263771234567", requiredMode = Schema.RequiredMode.REQUIRED,
                    description = "A phone number (any common spelling), an email address, or a support reference "
                            + "(SEC-…). Card, voucher and collection codes are refused.")
            String q) {
    }

    @Schema(name = "SupportSearchResult")
    public record SearchResult(
            @Schema(example = "SLK-7Q2M9X", description = "Send this with every detail read and write for the next "
                    + "30 minutes. Bound to you: a colleague's lookupId is refused.")
            String lookupId,
            QueryView query,
            @Schema(description = "Built ONLY from the sections you can see.") CustomerView customer,
            List<IdentityWarning> identityWarnings,
            @Schema(description = "One entry per section you can see: console, innbucksApp (more sections arrive "
                    + "without a shape change).")
            Map<String, SectionView<?>> sections,
            @Schema(nullable = true, description = "Set for a reference search: the reference and the one section "
                    + "that owns it. Null for a phone or email search.")
            FocusView focus,
            @Schema(example = "[]", description = "Sections that exist but that you can't see.") List<String> notShown,
            @Schema(example = "false", description = "True when this customer matches an InnBucks staff account. "
                    + "Writes then need a supervisor, and the lookup is alerted.")
            boolean staffAccount) {
    }

    @Schema(name = "SupportQuery")
    public record QueryView(
            @Schema(example = "PHONE", description = "PHONE, EMAIL or DTX_REFERENCE") String kind,
            @Schema(example = "+263771234567") String normalised) {
    }

    @Schema(name = "SupportCustomer", description = "The customer's OWN phone and email in full, so you can verify "
            + "the caller. Null where the visible sections don't agree on one value.")
    public record CustomerView(
            @Schema(example = "+263771234567", nullable = true) String phone,
            @Schema(example = "tariro@example.com", nullable = true) String email,
            @Schema(example = "Tariro Moyo", nullable = true) String name) {
    }

    @Schema(name = "SupportIdentityWarning")
    public record IdentityWarning(
            @Schema(example = "phone_email_different_accounts") String code,
            @Schema(example = "This phone number and this email belong to different accounts. Check which one the "
                    + "caller means before acting.") String message) {
    }

    @Schema(name = "SupportFocus")
    public record FocusView(
            @Schema(example = "innbucksApp") String section,
            @Schema(example = "DTX_REFERENCE") String kind,
            @Schema(example = "SEC-8F2KQ7") String reference,
            @Schema(example = "To see this customer's other products, search by their phone or email.") String note) {
    }

    @Schema(name = "SupportSection")
    public record SectionView<T>(
            @Schema(example = "OK", description = "OK, NOT_FOUND or UNAVAILABLE") String status,
            @Schema(example = "phone", nullable = true, description = "phone, email or reference") String matchedBy,
            @Schema(example = "1 Foundry console account: Tariro Moyo (MERCHANT_ADMIN).") String summary,
            @Schema(example = "Locked after too many wrong passwords until 14:30. After verifying the caller you can "
                    + "unlock it now.", nullable = true) String agentGuidance,
            @Schema(nullable = true) T data) {
    }

    // ---- console section ---------------------------------------------------------------------

    @Schema(name = "SupportConsoleSection")
    public record ConsoleSectionData(
            @Schema(description = "One entry per console account the search reached. An InnBucks staff account "
                    + "(SUPER_ADMIN included) is only a stub — staffAccount: true, the guidance and no actions — "
                    + "never its id, roles, second factor, lockout, sign-in or contact details.")
            List<? extends ConsoleAccountEntry> accounts) {
    }

    /**
     * One account in the console section: the full {@link ConsoleAccountView},
     * or the {@link StaffAccountStub} a staff account gets instead. Sealed, so a
     * third shape cannot appear without this file (and its allow-list test)
     * changing.
     */
    @Schema(name = "SupportConsoleAccountEntry", oneOf = {ConsoleAccountView.class, StaffAccountStub.class})
    public sealed interface ConsoleAccountEntry permits ConsoleAccountView, StaffAccountStub {
        boolean staffAccount();

        String agentGuidance();

        List<String> actions();
    }

    /**
     * What an agent sees of an InnBucks STAFF account (SUPER_ADMIN included):
     * that it is one, and whom to ask. Nothing that describes it — support can't
     * act on it, and a colleague's (or the platform owner's) roles, second
     * factor, lockout, sign-in history and contact details are not something a
     * {@code support-console:read} holder needs, or may browse. It carries no id,
     * and the lookup records none, so no detail read or write can be aimed at it.
     */
    @Schema(name = "SupportConsoleStaffAccount")
    public record StaffAccountStub(
            @Schema(example = "true") boolean staffAccount,
            @Schema(example = "This is an InnBucks staff account; ask a SUPER_ADMIN. Support can't see or change it.")
            String agentGuidance,
            @Schema(example = "[]") List<String> actions) implements ConsoleAccountEntry {
    }

    @Schema(name = "SupportConsoleAccount")
    public record ConsoleAccountView(
            @Schema(example = "1042", description = "users.id — the {id} of /admin/support/console-users/{id}/…") Long userId,
            @Schema(example = "9b2f4c1e-6a7d-4e0b-8c3f-2d5e1a7b9c40") UUID userUuid,
            @Schema(example = "Tariro Moyo") String name,
            @Schema(example = "tariro@example.com", nullable = true) String email,
            @Schema(example = "+263771234567", nullable = true) String phone,
            @Schema(example = "ACTIVE", description = "ACTIVE, DEACTIVATED or PENDING_APPROVAL") String status,
            @Schema(example = "[\"MERCHANT_ADMIN\"]") List<String> roles,
            @Schema(example = "false", description = "An InnBucks staff account: support can't act on it.") boolean staffAccount,
            @Schema(example = "true") boolean mfaEnrolled,
            @Schema(example = "2026-09-30T14:30:00+02:00", nullable = true, description = "Set only while locked.")
            LocalDateTime lockedUntil,
            @Schema(example = "null", nullable = true, description = "Set only while the 2FA step is locked.")
            LocalDateTime mfaLockedUntil,
            @Schema(example = "5") int failedSignInAttempts,
            @Schema(example = "2026-09-29T08:12:40+02:00", nullable = true) LocalDateTime lastSignInAt,
            @Schema(example = "false", description = "They must replace a temporary password at their next sign-in.")
            boolean mustChangePassword,
            @Schema(example = "2026-03-02T10:15:00+02:00") LocalDateTime createdAt,
            List<OrganizationView> organizations,
            List<ServiceRequestView> serviceRequests,
            @Schema(example = "Locked after too many wrong passwords until 14:30. After verifying the caller you can "
                    + "unlock it now.") String agentGuidance,
            @Schema(example = "[\"unlock\",\"send-password-reset\",\"mfa/reset\"]",
                    description = "The actions that apply to this account now, among those you hold — only ones "
                            + "the server would accept.")
            List<String> actions) implements ConsoleAccountEntry {
    }

    @Schema(name = "SupportConsoleOrganization")
    public record OrganizationView(
            @Schema(example = "5c0e8a2d-31f4-4b6e-9d7a-0f1e2d3c4b5a") UUID organizationId,
            @Schema(example = "Moyo Fresh Foods") String name,
            @Schema(example = "OWNER", description = "OWNER, ADMIN or STAFF") String role,
            @Schema(example = "ACTIVE", description = "ACTIVE or SUSPENDED") String status,
            @Schema(example = "[\"loyalty\"]") List<String> products) {
    }

    @Schema(name = "SupportConsoleServiceRequest")
    public record ServiceRequestView(
            @Schema(example = "311") Long id,
            @Schema(example = "marketplace") String service,
            @Schema(example = "PENDING", description = "PENDING, APPROVED or REJECTED") String status,
            @Schema(example = "2026-09-28T11:05:00+02:00") LocalDateTime submittedAt,
            @Schema(example = "null", nullable = true) LocalDateTime decidedAt,
            @Schema(example = "null", nullable = true, description = "The reviewer's reason, on a rejection.")
            String decisionReason,
            @Schema(example = "Waiting for an InnBucks administrator to review it (submitted 11:05 on 28 Sep). "
                    + "Support can't approve requests; if it's urgent, escalate to the product team.")
            String guidance) {
    }

    // ---- InnBucks 2.0 app section -----------------------------------------------------------

    @Schema(name = "SupportInnbucksAppSection")
    public record InnbucksAppSectionData(List<InnbucksAppPhoneView> phones) {
    }

    @Schema(name = "SupportInnbucksAppPhone")
    public record InnbucksAppPhoneView(
            @Schema(example = "+263771234567") String msisdn,
            @Schema(nullable = true, description = "Null when no InnBucks account uses this number.")
            CustomerProfileView customerProfile,
            @Schema(description = "The DTX device overview. lastIp and event addresses are masked; install ids are "
                    + "never shown.")
            DeviceSecurityView deviceSecurity) {
    }

    @Schema(name = "SupportInnbucksCustomerProfile")
    public record CustomerProfileView(
            @Schema(example = "2") int registrationTier,
            @Schema(example = "false") boolean verified,
            @Schema(example = "true") boolean phoneVerified,
            @Schema(example = "2026-07-02T08:15:52+02:00", nullable = true) LocalDateTime phoneVerifiedAt) {
    }

    @Schema(name = "SupportInnbucksDeviceSecurity")
    public record DeviceSecurityView(
            ProfileView profile,
            List<SupportDeviceView> devices,
            CountersView counters,
            List<EventView> recentEvents,
            @Schema(example = "1 phone signed in.") String summary) {
    }

    // ---- writes ------------------------------------------------------------------------------

    @Schema(name = "SupportWriteRequest")
    public record WriteRequest(
            @Size(max = 16, message = "lookupId must be 16 characters or fewer")
            @Schema(example = "SLK-7Q2M9X", requiredMode = Schema.RequiredMode.REQUIRED,
                    description = "The lookupId of YOUR search for this customer, at most 30 minutes old.")
            String lookupId,
            @NotBlank(message = "note is required")
            // The same bound the seal keeps (MfaService.cleanNote): a longer note
            // would be cut short on the chain without anyone being told.
            @Size(max = 500, message = "note must be 500 characters or fewer")
            @Schema(example = "Caller verified by date of birth and last sign-in time; locked out after a password "
                    + "change.", requiredMode = Schema.RequiredMode.REQUIRED, maxLength = 500,
                    description = "Why — sealed on the audit chain, with markup and invisible characters removed. A "
                            + "note that is empty once cleaned is 400 note_required.")
            String note,
            @Size(max = 64, message = "caseId must be 64 characters or fewer")
            @Schema(example = "null", nullable = true, description = "Reserved for support cases (PR 6). Recorded "
                    + "with the action (support_actions.case_id) but not yet checked against any case.")
            String caseId) {
    }

    @Schema(name = "SupportConsoleActionResult")
    public record ActionResult(
            @Schema(example = "SUCCESS", description = "SUCCESS for an in-process console action") String outcome,
            @Schema(example = "The account can sign in again now. If the caller is still refused, ask them to wait a "
                    + "minute and try once more.") String whatHappensNext,
            @Schema(example = "false", description = "True when this answer is the stored outcome of an earlier "
                    + "request with the same Idempotency-Key — nothing was done twice.") boolean replayed,
            @Schema(description = "The account as it is now (a staff stub if it has become a staff account since; "
                    + "null if it no longer exists).", nullable = true) ConsoleAccountEntry account) {
    }
}
