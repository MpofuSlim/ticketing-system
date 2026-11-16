package com.innbucks.userservice.devicesecurity;

import com.innbucks.userservice.config.MarketTimeZone;
import com.innbucks.userservice.util.SmsTextSanitizer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The copy rules are load-bearing, so they are pinned: every SMS text must
 * survive the gateway sanitizer UNCHANGED (else the gateway rewrites or rejects
 * it), every message is one line (the WhatsApp sender drops everything after a
 * line break), none carries a link, and times are the market's wall clock. The
 * one exception to one line is an SMS code carrying the Super app's SMS Retriever
 * hash, which must end the message alone on its own line within 140 bytes.
 */
class DeviceSecurityMessagesTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 29, 7, 58); // 09:58 in Harare
    private final DeviceSecurityProperties props = new DeviceSecurityProperties();
    private final DeviceSecurityMessages messages;

    DeviceSecurityMessagesTest() {
        props.setSupportPhone("0867 700 0000");
        messages = new DeviceSecurityMessages(new MarketTimeZone("ZW"), props);
    }

    private DeviceNotice notice(DeviceNotice.Type type) {
        return new DeviceNotice(type, "+263771234512", "Samsung SM-A155F", NOW, NOW.plusMinutes(32), "SEC-8F2KQ7",
                null, false);
    }

    @Test
    @DisplayName("every SMS text survives SmsTextSanitizer unchanged")
    void smsCopy_isGatewaySafe() {
        for (DeviceNotice.Type type : DeviceNotice.Type.values()) {
            String sms = messages.notice(notice(type), OtpChannel.SMS, NOW);
            assertThat(SmsTextSanitizer.toGsmSafe(sms)).as(type.name()).isEqualTo(sms);
        }
        String otp = messages.otp("482913", 5, OtpChannel.SMS);
        assertThat(SmsTextSanitizer.toGsmSafe(otp)).isEqualTo(otp);
        props.getOtp().setSmsAppHash(HASH);
        for (boolean warning : new boolean[]{false, true}) {
            String withHash = messages.otp("482913", 5, OtpChannel.SMS, warning);
            assertThat(SmsTextSanitizer.toGsmSafe(withHash)).as("warning=" + warning).isEqualTo(withHash);
        }
    }

    @Test
    @DisplayName("every message is one line with no link, on both channels")
    void everyMessage_isOneLineWithoutLinks() {
        for (OtpChannel channel : OtpChannel.values()) {
            for (DeviceNotice.Type type : DeviceNotice.Type.values()) {
                String text = messages.notice(notice(type), channel, NOW);
                assertThat(text).as(type + "/" + channel).doesNotContain("\n", "\r", "http", "www.", ".com");
            }
            assertThat(messages.otp("482913", 5, channel)).doesNotContain("\n");
        }
    }

    private static final String HASH = "GcOlMYQc0Wf";

    @Test
    @DisplayName("with the app hash set, every SMS code ends with it alone on its last line, within 140 bytes, for "
            + "both wordings and every code lifetime up to 99 minutes; the only 6-digit number is the code")
    void smsCode_withAppHash_endsWithItAlone_within140Bytes() {
        props.getOtp().setSmsAppHash(HASH);
        assertThat(messages.otp("482913", 5, OtpChannel.SMS, false)).isEqualTo(
                "InnBucks code 482913, valid 5 minutes. Never share it, InnBucks will never ask for it.\n" + HASH);
        assertThat(messages.otp("482913", 5, OtpChannel.SMS, true)).isEqualTo(
                "InnBucks code 482913, valid 5 minutes. Never share it, InnBucks will never ask for it. "
                        + "Using it signs out other phones.\n" + HASH);
        assertThat(bytes(messages.otp("482913", 5, OtpChannel.SMS, false))).isEqualTo(98);
        assertThat(bytes(messages.otp("482913", 5, OtpChannel.SMS, true))).isEqualTo(131);

        for (boolean warning : new boolean[]{false, true}) {
            for (long minutes = 1; minutes <= 99; minutes++) {
                String sms = messages.otp("482913", minutes, OtpChannel.SMS, warning);
                String[] lines = sms.split("\n", -1);
                assertThat(lines).as(sms).hasSize(2);
                assertThat(lines[1]).isEqualTo(HASH);
                assertThat(lines[0]).doesNotContain("\r", HASH);
                assertThat(bytes(sms)).as(sms).isLessThanOrEqualTo(DeviceSecurityMessages.SMS_RETRIEVER_MAX_BYTES);
                Matcher sixDigits = Pattern.compile("(?<!\\d)\\d{6}(?!\\d)").matcher(sms);
                assertThat(sixDigits.find()).isTrue();
                assertThat(sixDigits.group()).isEqualTo("482913");
                assertThat(sixDigits.find()).as(sms).isFalse();
            }
        }
    }

    @Test
    @DisplayName("the app hash rides SMS only: WhatsApp codes and every notice stay one line without it")
    void appHash_neverOnWhatsAppOrNotices() {
        props.getOtp().setSmsAppHash(HASH);
        for (boolean warning : new boolean[]{false, true}) {
            assertThat(messages.otp("482913", 5, OtpChannel.WHATSAPP, warning)).doesNotContain(HASH, "\n");
        }
        for (OtpChannel channel : OtpChannel.values()) {
            for (DeviceNotice.Type type : DeviceNotice.Type.values()) {
                assertThat(messages.notice(notice(type), channel, NOW)).as(type + "/" + channel)
                        .doesNotContain(HASH, "\n");
            }
        }
    }

    @Test
    @DisplayName("a hash is trimmed; one that is not 11 letters, digits or '+' (the gateway refuses '/') is left off, "
            + "and the SMS code is the one-line text")
    void appHash_trimmed_andUnusableOnesLeftOff() {
        props.getOtp().setSmsAppHash("  " + HASH + " ");
        assertThat(messages.otp("482913", 5, OtpChannel.SMS)).endsWith("\n" + HASH);

        String oneLine = "InnBucks code 482913, valid 5 minutes. Never share it, InnBucks will never ask for it.";
        for (String bad : new String[]{"", "   ", "GcOlMYQc0W", "GcOlMYQc0Wf1", "GcOlMYQc0W/", "GcOlMYQc0W-",
                "GcOl MYQc0W", "GcOlMYQc0W=", "GcOlMYQc0W\u00e9"}) {
            props.getOtp().setSmsAppHash(bad);
            assertThat(messages.otp("482913", 5, OtpChannel.SMS)).as("[" + bad + "]").isEqualTo(oneLine);
        }
        props.getOtp().setSmsAppHash(null);
        assertThat(messages.otp("482913", 5, OtpChannel.SMS)).isEqualTo(oneLine);
        assertThat(DeviceSecurityMessages.appHashProblem("GcOlMYQc0W/", 5)).isEqualTo("is not 11 letters, digits or '+'");
        assertThat(DeviceSecurityMessages.appHashProblem("Gc+lMYQc0Wf", 5)).isNull();
    }

    @Test
    @DisplayName("a code lifetime that would push the longest SMS code past 140 bytes leaves the hash off EVERY code, "
            + "the short wording included: a hash is used for all codes or for none")
    void appHash_overBudget_isLeftOffEveryCode() {
        props.getOtp().setSmsAppHash(HASH);
        long minutes = 10_000_000_000L;
        assertThat(DeviceSecurityMessages.appHashProblem(HASH, minutes))
                .isEqualTo("would make a code SMS 141 bytes, over 140");
        assertThat(DeviceSecurityMessages.appHashProblem(HASH, 99)).isNull();
        for (boolean warning : new boolean[]{false, true}) {
            assertThat(messages.otp("482913", minutes, OtpChannel.SMS, warning)).as("warning=" + warning)
                    .doesNotContain("\n", HASH);
        }
    }

    private static int bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8).length;
    }

    @Test
    @DisplayName("WhatsApp says *569#; SMS spells it out, because the gateway strips the star")
    void ussdCode_perChannel() {
        String wa = messages.notice(notice(DeviceNotice.Type.BANNED_USSD_UNLOCKABLE), OtpChannel.WHATSAPP, NOW);
        String sms = messages.notice(notice(DeviceNotice.Type.BANNED_USSD_UNLOCKABLE), OtpChannel.SMS, NOW);
        assertThat(wa).contains("*569#");
        assertThat(sms).contains("star 569 hash").doesNotContain("*");
    }

    @Test
    @DisplayName("times are Harare wall-clock; SMS writes 14.30 because the gateway rejects colons")
    void times_areMarketLocal() {
        String wa = messages.notice(notice(DeviceNotice.Type.TEMP_BLOCKED), OtpChannel.WHATSAPP, NOW);
        String sms = messages.notice(notice(DeviceNotice.Type.TEMP_BLOCKED), OtpChannel.SMS, NOW);
        assertThat(wa).contains("paused until 10:30.");
        assertThat(sms).contains("paused until 10.30.");
        assertThat(messages.tempBlockedLine(NOW.plusDays(1), "SEC-8F2KQ7", NOW)).contains("09:58 on 30 Sep");
    }

    @Test
    void theOtpNamesTheCodeAndItsLifetime() {
        assertThat(messages.otp("482913", 5, OtpChannel.WHATSAPP))
                .isEqualTo("InnBucks code: 482913. It expires in 5 minutes. Never share it — InnBucks will never ask for it.");
    }

    @Test
    @DisplayName("the app lines are neutral: nothing names the check that fired (§3 rule 6)")
    void appLines_areNeutral() {
        assertThat(messages.otpRequiredLine()).isEqualTo("Let's confirm it's you on this phone.");
        assertThat(messages.bannedLine(true, "SEC-8F2KQ7")).contains("dial *569#").contains("SEC-8F2KQ7");
        assertThat(messages.bannedLine(false, "SEC-8F2KQ7")).contains("0867 700 0000").doesNotContain("569");
        assertThat(messages.tempBlockedLine(null, "SEC-8F2KQ7", NOW)).contains("security check fails");
    }

    @Test
    void ussdMenuLabel_namesTheDay() {
        assertThat(messages.ussdMenuLabel("Samsung SM-A155F", LocalDateTime.of(2026, 9, 28, 17, 2), true))
                .isEqualTo("Samsung SM-A155F (blocked 28 Sep)");
    }

    // ---- One phone per number ---------------------------------------------------------

    private static DeviceNotice phoneReplaced(String label) {
        // Published by DeviceEnforcement.bind exactly like this: no until/ref/channel, SMS first.
        return new DeviceNotice(DeviceNotice.Type.PHONE_REPLACED, "+263771234512", label, NOW, null, null, null, true);
    }

    @Test
    @DisplayName("PHONE_REPLACED names the phone now signed in, never as 'your' phone; SMS spells the USSD code and "
            + "writes 09.58, WhatsApp adds the support number for a customer whose SIM was taken")
    void phoneReplaced_exactCopy_perChannel() {
        assertThat(messages.notice(phoneReplaced("Samsung SM-A155F"), OtpChannel.SMS, NOW))
                .isEqualTo("InnBucks is now signed in only on Samsung SM-A155F, since 09.58. "
                        + "If this wasn't you, dial star 569 hash now.");
        assertThat(messages.notice(phoneReplaced("Samsung SM-A155F"), OtpChannel.WHATSAPP, NOW))
                .isEqualTo("InnBucks is now signed in only on Samsung SM-A155F, since 09:58. "
                        + "If this wasn't you, dial *569# now, or call InnBucks support on 0867 700 0000 "
                        + "if your SIM isn't working.");

        DeviceSecurityMessages noSupportPhone = new DeviceSecurityMessages(new MarketTimeZone("ZW"),
                new DeviceSecurityProperties());
        assertThat(noSupportPhone.notice(phoneReplaced("Samsung SM-A155F"), OtpChannel.WHATSAPP, NOW))
                .isEqualTo("InnBucks is now signed in only on Samsung SM-A155F, since 09:58. "
                        + "If this wasn't you, dial *569# now, or call InnBucks support if your SIM isn't working.");
        assertThat(noSupportPhone.notice(phoneReplaced("Samsung SM-A155F"), OtpChannel.SMS, NOW))
                .isEqualTo("InnBucks is now signed in only on Samsung SM-A155F, since 09.58. "
                        + "If this wasn't you, dial star 569 hash now.");
    }

    @Test
    @DisplayName("PHONE_REPLACED stays one 160-character SMS segment even for a 70-character label read the next "
            + "day: the label is clipped to 58, the copy survives the sanitizer and never calls the phone 'your'")
    void phoneReplaced_fitsOneSmsSegment() {
        String label = "Samsung Galaxy A15 5G Enterprise Edition Dual SIM SM-A155F-DSN Lt Blue";
        assertThat(label).hasSize(70);
        String clipped = "Samsung Galaxy A15 5G Enterprise Edition Dual SIM SM-A155F";
        assertThat(clipped).hasSize(DeviceSecurityMessages.REPLACED_LABEL_MAX);
        LocalDateTime nextDay = NOW.plusDays(1);

        String sms = messages.notice(phoneReplaced(label), OtpChannel.SMS, nextDay);
        assertThat(sms).isEqualTo("InnBucks is now signed in only on " + clipped
                + ", since 09.58 on 29 Sep. If this wasn't you, dial star 569 hash now.");
        assertThat(sms.length()).isLessThanOrEqualTo(160);
        assertThat(SmsTextSanitizer.toGsmSafe(sms)).isEqualTo(sms);
        assertThat(sms).doesNotContain("\n", "\r", "on your", label);
        assertThat(sms.substring(0, sms.indexOf(clipped))).doesNotContain("your ");

        String wa = messages.notice(phoneReplaced(label), OtpChannel.WHATSAPP, nextDay);
        assertThat(wa).startsWith("InnBucks is now signed in only on " + clipped + ", since 09:58 on 29 Sep. ")
                .doesNotContain("\n", "\r", "on your", label);
        assertThat(wa.substring(0, wa.indexOf(clipped))).doesNotContain("your ");
    }

    @Test
    @DisplayName("with one phone per number, the sign-in code says using it signs any other phone out; the SMS "
            + "stays one gateway-safe segment, and the 3-argument form is the code without the warning")
    void otp_withSignOutWarning() {
        String sms = messages.otp("482913", 5, OtpChannel.SMS, true);
        assertThat(sms).isEqualTo("InnBucks code 482913, valid 5 minutes. Never share it, InnBucks will never ask "
                + "for it. Using it signs out other phones.");
        assertThat(messages.otp("482913", 5, OtpChannel.SMS, false)).isEqualTo("InnBucks code 482913, valid 5 "
                + "minutes. Never share it, InnBucks will never ask for it.");
        assertThat(sms.length()).isLessThanOrEqualTo(160);
        assertThat(SmsTextSanitizer.toGsmSafe(sms)).isEqualTo(sms);
        assertThat(messages.otp("482913", 5, OtpChannel.WHATSAPP, true))
                .isEqualTo("InnBucks code: 482913. It expires in 5 minutes. Never share it — InnBucks will never "
                        + "ask for it. Using it signs InnBucks out on any other phone.");

        for (OtpChannel channel : OtpChannel.values()) {
            assertThat(messages.otp("482913", 5, channel)).as(channel.name())
                    .isEqualTo(messages.otp("482913", 5, channel, false))
                    .doesNotContain("signs InnBucks out", "signs out");
        }
    }

    @Test
    @DisplayName("the TOKEN line before a PIN that will sign another phone out warns about it, in one neutral line")
    void tokenLineSigningOutOthers_exactText() {
        assertThat(messages.tokenLineSigningOutOthers())
                .isEqualTo("Enter your PIN to continue. Any other phone will be signed out of InnBucks.");
        assertThat(messages.tokenLine()).isEqualTo("Enter your PIN to continue.");
    }

    @Test
    @DisplayName("the *569# Block menu marks the phone signed in right now; any other phone keeps its "
            + "market-day 'last used' label")
    void ussdActiveMenuLabel_marksTheSignedInPhone() {
        LocalDateTime lastSeen = LocalDateTime.of(2026, 9, 28, 22, 30); // 00:30 on 29 Sep in Harare
        assertThat(messages.ussdActiveMenuLabel("Samsung SM-A155F", lastSeen, true))
                .isEqualTo("Samsung SM-A155F (signed in)");
        assertThat(messages.ussdActiveMenuLabel("Samsung SM-A155F", null, true))
                .isEqualTo("Samsung SM-A155F (signed in)");
        assertThat(messages.ussdActiveMenuLabel("Samsung SM-A155F", lastSeen))
                .isEqualTo("Samsung SM-A155F (last used 29 Sep)");
        assertThat(messages.ussdActiveMenuLabel("Samsung SM-A155F", lastSeen, false))
                .isEqualTo("Samsung SM-A155F (last used 29 Sep)");
        assertThat(messages.ussdActiveMenuLabel("Samsung SM-A155F", null)).isEqualTo("Samsung SM-A155F");
    }

    /** A control character, a line separator or a paragraph separator: anything that ends a line. */
    private static final String LINE_BREAKING = "[\\p{Cc}\\p{Zl}\\p{Zp}]";

    @Test
    @DisplayName("PHONE_REPLACED for a phone whose model carries a line break stays ONE line on both channels: the "
            + "WhatsApp sender drops everything after a break, so a crafted model must not hide 'call InnBucks "
            + "support'; the SMS still survives the sanitizer unchanged")
    void phoneReplaced_aLabelWithALineBreak_staysOneLine() {
        // Built exactly as DeviceEnforcement.shortLabel(d) builds it from the stored model.
        String label = DeviceIdentity.shortLabel("samsung", "SM-A155F\nFake line", "android");
        assertThat(label).isEqualTo("Samsung SM-A155F Fake line");

        String wa = messages.notice(phoneReplaced(label), OtpChannel.WHATSAPP, NOW);
        assertThat(wa).isEqualTo("InnBucks is now signed in only on Samsung SM-A155F Fake line, since 09:58. "
                + "If this wasn't you, dial *569# now, or call InnBucks support on 0867 700 0000 "
                + "if your SIM isn't working.");
        assertThat(wa).doesNotContain("\n", "\r", " ").doesNotContainPattern(LINE_BREAKING)
                .contains("call InnBucks support");

        String sms = messages.notice(phoneReplaced(label), OtpChannel.SMS, NOW);
        assertThat(sms).isEqualTo("InnBucks is now signed in only on Samsung SM-A155F Fake line, since 09.58. "
                + "If this wasn't you, dial star 569 hash now.");
        assertThat(sms).doesNotContain("\n", "\r", " ").doesNotContainPattern(LINE_BREAKING)
                .endsWith("If this wasn't you, dial star 569 hash now.");
        assertThat(SmsTextSanitizer.toGsmSafe(sms)).isEqualTo(sms);

        // Every other breaker, and one long enough to be clipped: the copy after the label always survives.
        for (String breaker : new String[]{"\r\n", " ", " ", "\u0085", "\t", "\013"}) {
            for (String model : new String[]{"SM-A155F" + breaker + "Fake line",
                    "SM-A155F" + breaker + "Fake line " + "x".repeat(80)}) {
                String l = DeviceIdentity.shortLabel("samsung", model, "android");
                String w = messages.notice(phoneReplaced(l), OtpChannel.WHATSAPP, NOW);
                String s = messages.notice(phoneReplaced(l), OtpChannel.SMS, NOW);
                assertThat(w).as("WhatsApp " + model.length()).doesNotContainPattern(LINE_BREAKING)
                        .startsWith("InnBucks is now signed in only on Samsung SM-A155F Fake line")
                        .endsWith(", or call InnBucks support on 0867 700 0000 if your SIM isn't working.");
                assertThat(s).as("SMS " + model.length()).doesNotContainPattern(LINE_BREAKING)
                        .startsWith("InnBucks is now signed in only on Samsung SM-A155F Fake line")
                        .endsWith("If this wasn't you, dial star 569 hash now.");
                assertThat(s.length()).isLessThanOrEqualTo(160);
                assertThat(SmsTextSanitizer.toGsmSafe(s)).isEqualTo(s);
            }
        }

        // The same label on the *569# Block menu is one menu line, not two.
        assertThat(messages.ussdActiveMenuLabel(label, null, true)).isEqualTo("Samsung SM-A155F Fake line (signed in)");
    }
}
