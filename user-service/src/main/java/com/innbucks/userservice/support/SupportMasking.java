package com.innbucks.userservice.support;

import com.innbucks.userservice.util.MsisdnMasking;

/**
 * The masks the support surface uses (design §3.1.4). The customer's OWN phone
 * and email are shown in full in a response — the agent verifies the caller
 * with them — but everything that reaches a log, the access log's
 * {@code query_masked}, or the audit chain's target is masked here.
 */
public final class SupportMasking {

    private SupportMasking() {
    }

    /** {@code ****4567} — the fleet's MSISDN mask. */
    public static String phone(String phone) {
        return MsisdnMasking.mask(phone);
    }

    /** {@code t****@example.com}; {@code ****} for anything that is not an address. */
    public static String email(String email) {
        if (email == null) return "****";
        int at = email.lastIndexOf('@');
        if (at <= 0 || at == email.length() - 1) return "****";
        return email.charAt(0) + "****" + email.substring(at);
    }

    /**
     * An address the support section must not show whole (the phone's last IP):
     * IPv4 keeps its first two octets ({@code 41.221.x.x}); IPv6 its first two
     * groups ({@code 2c0f:f4c0:…}); anything else is withheld.
     */
    public static String ip(String ip) {
        if (ip == null || ip.isBlank()) return null;
        String v = ip.strip();
        if (v.matches("^\\d{1,3}(\\.\\d{1,3}){3}$")) {
            String[] o = v.split("\\.");
            return o[0] + "." + o[1] + ".x.x";
        }
        if (v.indexOf(':') >= 0) {
            String[] g = v.split(":");
            if (g.length >= 2 && !g[0].isEmpty() && !g[1].isEmpty()) return g[0] + ":" + g[1] + ":…";
            return "…";
        }
        return "…";
    }

    /** The key the audit seal and support_actions name: {@code msisdn:****4567}, {@code email:t****@x.com}. */
    public static String customerKey(String phone, String email) {
        if (phone != null && !phone.isBlank()) return "msisdn:" + phone(phone);
        if (email != null && !email.isBlank()) return "email:" + email(email);
        return "unknown";
    }

    /** What {@code support_access_log.query_masked} stores for a supported kind; refusals store nothing. */
    public static String query(SupportQueryClassifier.Query q) {
        if (q == null || q.value() == null) return null;
        return switch (q.kind()) {
            case EMAIL -> email(q.value());
            case PHONE -> phone(q.value());
            // A DTX reference is not a personal identifier: kept whole so a
            // review can match it to the device-security events.
            case DTX_REFERENCE -> q.value();
            default -> null;
        };
    }
}
