package com.innbucks.userservice.config;

import com.innbucks.userservice.client.EmailNotificationClient;
import com.innbucks.userservice.exception.StaffPolicyException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Boot-time and request-time provisioning check for staff accounts (V44).
 *
 * <p>The failure this exists to catch is HALF-PROVISIONING: staff accounts
 * switched on for a cell while one of the pieces they need is missing, so the
 * feature looks live and every attempt fails. ERROR at boot, never a boot
 * failure — a staff-onboarding problem must not stop a cell starting:
 * <ul>
 *   <li><b>No staff domain</b> ({@code STAFF_ALLOWED_EMAIL_DOMAINS} blank): every
 *       staff grant answers {@code 503 staff_domains_unconfigured}.</li>
 *   <li><b>No console URL</b> ({@code STAFF_CONSOLE_BASE_URL} blank), or <b>no
 *       email transport</b> — SMTP off/unconfigured AND the notification API
 *       unconfigured (blank {@code BANK_API_*}): {@code POST /admin/staff} and
 *       resend-invite answer {@code 503 staff_invites_unconfigured} BEFORE
 *       creating anything, so no account is ever left waiting for an invite
 *       that could not be sent.</li>
 * </ul>
 *
 * <p>The invite goes through the same path as every other email
 * ({@code EmailNotificationClient.sendEmail}): SES first when
 * {@code MAIL_ENABLED=true}, the notification API as fallback. The resolved
 * console URL is logged at INFO so a staging host pointing at production (or the
 * reverse) is visible in the boot log.
 */
@Slf4j
@Component
public class StaffProvisioningCheck {

    private final StaffAccountProperties properties;
    private final EmailNotificationClient email;

    public StaffProvisioningCheck(StaffAccountProperties properties, EmailNotificationClient email) {
        this.properties = properties;
        this.email = email;
    }

    /** True when both a console URL and at least one email transport are available. */
    public boolean invitesProvisioned() {
        return !properties.consoleBaseUrl().isBlank() && (email.smtpEnabled() || email.apiConfigured());
    }

    /** 503 {@code staff_invites_unconfigured} when an invite could not be sent from this cell. */
    public void requireInvitesProvisioned() {
        if (!invitesProvisioned()) {
            throw StaffPolicyException.staffInvitesUnconfigured();
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void checkStaffProvisioning() {
        if (!properties.domainsConfigured()) {
            log.error("Staff accounts HALF-PROVISIONED: STAFF_ALLOWED_EMAIL_DOMAINS is blank, so every staff grant "
                    + "(POST /admin/staff, adding a staff role, adding a platform permission to a held role) is "
                    + "refused 503 staff_domains_unconfigured. Set it in deploy/cells/cell.<iso>.env.");
        }
        if (properties.consoleBaseUrl().isBlank()) {
            log.error("Staff accounts HALF-PROVISIONED: STAFF_CONSOLE_BASE_URL is blank, so no invite link can be "
                    + "built and POST /admin/staff answers 503 staff_invites_unconfigured.");
        } else {
            log.info("Staff invites link to {}{}#token=… (invite TTL {}, enforcement {})",
                    properties.consoleBaseUrl(), properties.getInvitePath(), properties.getInviteTtl(),
                    properties.getEligibilityEnforcement().name().toLowerCase(java.util.Locale.ROOT));
        }
        if (!email.smtpEnabled() && !email.apiConfigured()) {
            log.error("Staff accounts HALF-PROVISIONED: no email transport — SMTP is off or unconfigured "
                    + "(MAIL_ENABLED / MAIL_HOST / MAIL_FROM) and the notification API is unconfigured "
                    + "(BANK_API_URL / BANK_API_KEY / BANK_API_USERNAME / BANK_API_PASSWORD), so POST /admin/staff "
                    + "answers 503 staff_invites_unconfigured.");
        }
    }
}
