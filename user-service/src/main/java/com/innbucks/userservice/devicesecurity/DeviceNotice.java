package com.innbucks.userservice.devicesecurity;

import java.time.LocalDateTime;

/**
 * A security notification to send once the transaction that caused it commits
 * (§10). Published as a Spring event; {@link DeviceSecurityNotifier} delivers it
 * after commit, off the request thread, and never lets a delivery failure reach
 * the caller — a dead SMS gateway must not look like a failed unlock.
 *
 * @param preferredChannel the customer's chosen channel, or null to read it from their profile at
 *                 delivery time (the usual case — the last channel they picked for an OTP, §10)
 * @param smsFirst true for notices caused on USSD: the SIM that just dialled is
 *                 the strongest place to confirm to, so SMS goes first and
 *                 WhatsApp is the fallback. Otherwise the customer's chosen
 *                 channel goes first with SMS as the fallback.
 */
public record DeviceNotice(
        Type type,
        String msisdn,
        String deviceLabel,
        LocalDateTime at,
        LocalDateTime until,
        String supportRef,
        OtpChannel preferredChannel,
        boolean smsFirst) {

    public enum Type {
        NEW_DEVICE_BOUND,
        TEMP_BLOCKED,
        INTEGRITY_HOLD,
        BANNED_USSD_UNLOCKABLE,
        BANNED_SUPPORT_ONLY,
        CUSTOMER_BLOCKED,
        UNLOCKED_USSD,
        UNLOCKED_BY_SUPPORT,
        REVOKED,
        PIN_SET
    }
}
