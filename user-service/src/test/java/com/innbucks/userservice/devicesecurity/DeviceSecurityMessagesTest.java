package com.innbucks.userservice.devicesecurity;

import com.innbucks.userservice.config.MarketTimeZone;
import com.innbucks.userservice.util.SmsTextSanitizer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The copy rules are load-bearing, so they are pinned: every SMS text must
 * survive the gateway sanitizer UNCHANGED (else the gateway rewrites or rejects
 * it), every message is one line (the WhatsApp sender drops everything after a
 * line break), none carries a link, and times are the market's wall clock.
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
}
