package com.innbucks.userservice.devicesecurity;

import com.google.i18n.phonenumbers.NumberParseException;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.Phonenumber;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

/**
 * How a device and a number are named — to storage, to logs and to people.
 */
public final class DeviceIdentity {

    private static final PhoneNumberUtil PHONE_UTIL = PhoneNumberUtil.getInstance();

    private DeviceIdentity() {
    }

    /**
     * SHA-256 hex of the install id. The install id is a 122-bit random UUID, so
     * an unkeyed hash is enough (same argument as refresh-token hashes): nothing
     * about it is guessable, the hash only has to stop a DB reader from
     * presenting it as a device.
     */
    public static String hashInstallId(String installId) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] out = digest.digest(installId.trim().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(out);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /**
     * Log form of a number: everything but the last two digits masked (§12 — a
     * stricter mask than the last-4 {@code MsisdnMasking} the rest of the service
     * uses, because the contract asks for it on this surface).
     */
    public static String logMask(String msisdn) {
        if (msisdn == null || msisdn.length() <= 2) return "**";
        return "*".repeat(Math.min(8, msisdn.length() - 2)) + msisdn.substring(msisdn.length() - 2);
    }

    /**
     * What the customer sees as the destination of a code: {@code +263 77 *** **12}.
     * Enough to recognise their own number, not enough to learn someone else's.
     */
    public static String destinationMasked(String e164) {
        if (e164 == null || e164.isBlank()) return null;
        try {
            Phonenumber.PhoneNumber parsed = PHONE_UTIL.parse(e164, null);
            String national = String.valueOf(parsed.getNationalNumber());
            if (national.length() < 5) return "+" + parsed.getCountryCode() + " *** **";
            return "+" + parsed.getCountryCode() + " " + national.substring(0, 2) + " *** **"
                    + national.substring(national.length() - 2);
        } catch (NumberParseException e) {
            return logMask(e164);
        }
    }

    /**
     * A person-readable name for a device: "Samsung SM-A155F". The model is what
     * the OS reports; the manufacturer is dropped when the model already starts
     * with it ("iPhone 15" rather than "Apple iPhone 15" is fine either way).
     */
    public static String shortLabel(String manufacturer, String model, String platform) {
        String maker = capitalise(manufacturer);
        String m = model == null ? "" : model.trim();
        String label;
        if (m.isEmpty()) {
            label = maker.isEmpty() ? platformName(platform) + " phone" : maker + " phone";
        } else if (maker.isEmpty() || m.toLowerCase(Locale.ROOT).startsWith(maker.toLowerCase(Locale.ROOT))) {
            label = m;
        } else {
            label = maker + " " + m;
        }
        label = label.trim();
        return label.isEmpty() ? "phone" : truncate(label, 60);
    }

    /** The "Your devices" label (§5.6): "Samsung SM-A155F · Android 15". */
    public static String fullLabel(String manufacturer, String model, String platform, String osVersion) {
        String base = shortLabel(manufacturer, model, platform);
        String os = platformName(platform);
        if (os.isEmpty()) return base;
        String v = osVersion == null ? "" : osVersion.trim();
        return truncate(base + " · " + os + (v.isEmpty() ? "" : " " + v), 120);
    }

    private static String platformName(String platform) {
        if (platform == null) return "";
        return switch (platform.trim().toLowerCase(Locale.ROOT)) {
            case "android" -> "Android";
            case "ios" -> "iOS";
            case "" -> "";
            default -> capitalise(platform);
        };
    }

    private static String capitalise(String s) {
        if (s == null || s.isBlank()) return "";
        String t = s.trim();
        return t.substring(0, 1).toUpperCase(Locale.ROOT) + t.substring(1);
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
