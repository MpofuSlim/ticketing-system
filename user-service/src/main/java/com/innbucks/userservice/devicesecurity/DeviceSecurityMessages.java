package com.innbucks.userservice.devicesecurity;

import com.innbucks.userservice.config.MarketTimeZone;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Every word DTX puts in front of a customer: notification texts (§10), the
 * screen lines for each decision (§7) and the *569# menu texts (§9).
 *
 * <p>Rules the copy keeps, all load-bearing:
 * <ul>
 *   <li><b>Neutral</b> (§3 rule 6): nothing names the check that fired.</li>
 *   <li><b>No links</b> (§10), so a real message can never be confused with a
 *       phishing one.</li>
 *   <li><b>One line</b> (§10): the WhatsApp sender wraps the whole message in one
 *       template variable and silently drops anything after a line break.</li>
 *   <li><b>SMS-safe</b>: the InnBucks SMS gateway rejects {@code ! : / ? " * ;}
 *       outright (SmsTextSanitizer). So SMS copy writes times as {@code 14.30}
 *       and the USSD code as "star 569 hash" — the sanitizer would otherwise
 *       strip the star and tell the customer to dial "569#".</li>
 *   <li><b>Market time</b>: times are the wall clock of the market the cell
 *       serves, rendered here (the BE renders, the client parses nothing).</li>
 * </ul>
 */
@Component
public class DeviceSecurityMessages {

    public static final String USSD_CODE = "*569#";
    static final String USSD_CODE_SMS = "star 569 hash";

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH);

    private final MarketTimeZone marketTimeZone;
    private final String supportPhone;

    public DeviceSecurityMessages(MarketTimeZone marketTimeZone, DeviceSecurityProperties properties) {
        this.marketTimeZone = marketTimeZone;
        this.supportPhone = properties.getSupportPhone() == null ? "" : properties.getSupportPhone().trim();
    }

    // ---- Notifications (§10) -----------------------------------------------------

    /** The OTP itself. Never logged. */
    public String otp(String code, long minutes, OtpChannel channel) {
        return channel == OtpChannel.SMS
                ? "InnBucks code " + code + ". It expires in " + minutes + " minutes. Never share it, InnBucks will never ask for it."
                : "InnBucks code: " + code + ". It expires in " + minutes + " minutes. Never share it — InnBucks will never ask for it.";
    }

    public String notice(DeviceNotice n, OtpChannel channel, LocalDateTime nowUtc) {
        boolean sms = channel == OtpChannel.SMS;
        String ussd = sms ? USSD_CODE_SMS : USSD_CODE;
        String label = n.deviceLabel() == null ? "phone" : n.deviceLabel();
        return switch (n.type()) {
            case NEW_DEVICE_BOUND -> "A new phone (" + label + ") signed in to your InnBucks at "
                    + time(n.at(), nowUtc, sms) + ". If this wasn't you, dial " + ussd + " now.";
            case TEMP_BLOCKED -> "For your security, InnBucks sign-in on your " + label + " is paused until "
                    + time(n.until(), nowUtc, sms) + ". If this wasn't you, dial " + ussd + ".";
            case INTEGRITY_HOLD -> "For your security, InnBucks can't be used on your " + label
                    + " while its security check fails. Reference " + n.supportRef() + ".";
            case BANNED_USSD_UNLOCKABLE -> "Your " + label + " has been blocked from InnBucks for your security. "
                    + "To unlock it, dial " + ussd + " and choose Unlock device.";
            case BANNED_SUPPORT_ONLY -> "Your " + label + " has been blocked from InnBucks for your security. "
                    + "Please call InnBucks support" + (supportPhone.isEmpty() ? "" : " on " + supportPhone)
                    + " and quote " + n.supportRef() + ".";
            case CUSTOMER_BLOCKED -> "You blocked your " + label + " from InnBucks. To use it again, dial " + ussd
                    + " and choose Unlock device. Reference " + n.supportRef() + ".";
            case UNLOCKED_USSD -> "Your " + label + " is unlocked. Open InnBucks and confirm it's you with the code we send.";
            case UNLOCKED_BY_SUPPORT -> "InnBucks support unlocked your " + label
                    + ". Open InnBucks and confirm it's you with the code we send. If you didn't ask for this, dial "
                    + ussd + " now.";
            case REVOKED -> "Your " + label + " was removed from your InnBucks. If this wasn't you, dial " + ussd + " now.";
            case PIN_SET -> "Your InnBucks PIN was just set. If this wasn't you, dial " + ussd + " now.";
        };
    }

    // ---- What the app shows for each decision (§7) --------------------------------

    /**
     * The same line on every TOKEN, new phone or not. It used to read "You're on a
     * new phone. Some limits are lower until 11.58." during the cooling period, but
     * nothing that moves money reads cooling (the middleware applies its usual
     * step-up thresholds), so the line promised a protection that did not exist.
     * Bring it back in the change that makes a service actually lower the limits.
     */
    public String tokenLine() {
        return "Enter your PIN to continue.";
    }

    public String otpRequiredLine() {
        return "Let's confirm it's you on this phone.";
    }

    public String tempBlockedLine(LocalDateTime until, String ref, LocalDateTime nowUtc) {
        if (until == null) {
            return "This phone can't be used for InnBucks while its security check fails. Reference " + ref + ".";
        }
        return "For your security, sign-in on this phone is paused until " + time(until, nowUtc, false)
                + ". Reference " + ref + ".";
    }

    public String bannedLine(boolean ussdUnlock, String ref) {
        if (ussdUnlock) {
            return "This phone is blocked from InnBucks for your security. To unlock it, dial " + USSD_CODE
                    + " from your InnBucks number and choose Unlock device. Reference " + ref + ".";
        }
        return "This phone is blocked from InnBucks for your security. Please call InnBucks support"
                + (supportPhone.isEmpty() ? "" : " on " + supportPhone) + " and quote reference " + ref + ".";
    }

    public String supportPhone() {
        return supportPhone.isEmpty() ? null : supportPhone;
    }

    // ---- *569# screens (§9) ---------------------------------------------------------

    public String ussdNoBlockedPhones() {
        return "None of your phones is blocked from InnBucks. If you still can't sign in, call InnBucks support.";
    }

    public String ussdNoActivePhones() {
        return "There are no phones signed in to your InnBucks.";
    }

    public String ussdUnlocked() {
        return "Your phone is unlocked. Open InnBucks and sign in. We will send you a code to confirm it's you.";
    }

    public String ussdSupportOnly(String ref) {
        return "This phone can only be unlocked by InnBucks support. Call "
                + (supportPhone.isEmpty() ? "InnBucks support" : supportPhone) + ", reference " + ref + ".";
    }

    public String ussdTryLater(LocalDateTime when, LocalDateTime nowUtc) {
        return when == null
                ? "You can't unlock a phone on *569# right now. Please try again tomorrow."
                : "You can unlock this phone on *569# from " + time(when, nowUtc, false) + ". Please try again then.";
    }

    public String ussdIntegrityHold(String ref) {
        return "This phone can't be unlocked while its security check fails. Remove root or jailbreak, "
                + "then open InnBucks again. Reference " + ref + ".";
    }

    public String ussdNotFound() {
        return "We couldn't find that phone on your InnBucks. Please start again.";
    }

    public String ussdBlocked(String label, String ref) {
        return "Your " + label + " is now blocked from InnBucks. To use it again, dial *569# and choose "
                + "Unlock device. Reference " + ref + ".";
    }

    public String ussdAlreadyBlocked(String label) {
        return "Your " + label + " is already blocked from InnBucks.";
    }

    public String ussdBlockLimit() {
        return "You've blocked several phones today. For more help, please call InnBucks support.";
    }

    public String ussdChooseToUnlock() {
        return "Choose the phone to unlock:";
    }

    public String ussdChooseToBlock() {
        return "Choose the phone to block:";
    }

    /** "Samsung SM-A155F (last used 29 Sep)" — how an active phone is listed on the Block menu. */
    public String ussdActiveMenuLabel(String label, LocalDateTime lastSeenAt) {
        return lastSeenAt == null ? label : label + " (last used " + DAY.format(market(lastSeenAt)) + ")";
    }

    /** "Samsung SM-A155F (blocked 28 Sep)" — how a phone is listed on the menu (§9.1 step 4). */
    public String ussdMenuLabel(String label, LocalDateTime blockedAt, boolean blocked) {
        if (!blocked || blockedAt == null) return label;
        return label + " (blocked " + DAY.format(market(blockedAt)) + ")";
    }

    // ---- helpers -----------------------------------------------------------------------

    /**
     * "14:30" today, "14:30 on 30 Sep" otherwise; SMS uses "14.30". Public for
     * customer support's server-rendered sentences (design §3.1.6), which state
     * times the same way.
     */
    public String time(LocalDateTime utc, LocalDateTime nowUtc, boolean sms) {
        if (utc == null) return "";
        ZonedDateTime at = market(utc);
        LocalDate today = market(nowUtc).toLocalDate();
        String t = TIME.format(at);
        if (sms) t = t.replace(':', '.');
        return at.toLocalDate().equals(today) ? t : t + " on " + DAY.format(at);
    }

    private ZonedDateTime market(LocalDateTime utc) {
        return marketTimeZone.atMarketFromUtc(utc).atZoneSameInstant(marketTimeZone.zone());
    }
}
