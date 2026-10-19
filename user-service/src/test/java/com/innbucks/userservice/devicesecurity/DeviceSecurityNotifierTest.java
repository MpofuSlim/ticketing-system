package com.innbucks.userservice.devicesecurity;

import com.innbucks.userservice.client.NotificationDeliveryException;
import com.innbucks.userservice.client.SmsNotificationClient;
import com.innbucks.userservice.client.WhatsAppNotificationClient;
import com.innbucks.userservice.config.MarketTimeZone;
import com.innbucks.userservice.devicesecurity.entity.CustomerSecurityProfile;
import com.innbucks.userservice.devicesecurity.repository.CustomerSecurityProfileRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * How a notice is routed over the two channels. Every notice stops at the first
 * channel that delivers — except PHONE_REPLACED, which goes out on EVERY channel,
 * SMS first: the preferred channel was chosen by the phone that just signed in,
 * which after a SIM swap is the swapper's, so the owner must be reachable on the
 * other one too. The after-commit / async wiring is Spring's and is exercised by
 * DeviceSecurityFlowTest; this pins the routing and its metric on its own.
 */
class DeviceSecurityNotifierTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 29, 7, 58); // 09:58 in Harare
    private static final String ZW_NUMBER = "+263771234512";
    private static final String ZA_NUMBER = "+27821234567";

    private static final String REPLACED_SMS = "InnBucks is now signed in only on Samsung SM-A155F, since 09.58. "
            + "If this wasn't you, dial star 569 hash now.";
    private static final String REPLACED_WHATSAPP = "InnBucks is now signed in only on Samsung SM-A155F, since 09:58. "
            + "If this wasn't you, dial *569# now, or call InnBucks support on 0867 700 0000 if your SIM isn't working.";

    private WhatsAppNotificationClient whatsApp;
    private SmsNotificationClient sms;
    private CustomerSecurityProfileRepository profiles;
    private SimpleMeterRegistry meters;
    private DeviceSecurityMessages messages;
    private DeviceSecurityNotifier notifier;

    @BeforeEach
    void setUp() {
        whatsApp = mock(WhatsAppNotificationClient.class);
        sms = mock(SmsNotificationClient.class);
        profiles = mock(CustomerSecurityProfileRepository.class);
        meters = new SimpleMeterRegistry();
        DeviceSecurityProperties props = new DeviceSecurityProperties();
        props.setSupportPhone("0867 700 0000");
        messages = new DeviceSecurityMessages(new MarketTimeZone("ZW"), props);
        notifier = new DeviceSecurityNotifier(whatsApp, sms, messages, new DeviceSecurityMetrics(meters), profiles,
                Clock.fixed(NOW.toInstant(ZoneOffset.UTC), ZoneOffset.UTC), "ZW");
        // The customer's last OTP channel was WhatsApp: the case where "preferred first" and
        // "SMS first" disagree.
        when(profiles.findById(anyString())).thenAnswer(inv -> Optional.of(CustomerSecurityProfile.builder()
                .msisdn(inv.getArgument(0)).preferredChannel(OtpChannel.WHATSAPP).build()));
    }

    /** Exactly as DeviceEnforcement.bind publishes it: no channel of its own, SMS first. */
    private static DeviceNotice phoneReplaced(String msisdn) {
        return new DeviceNotice(DeviceNotice.Type.PHONE_REPLACED, msisdn, "Samsung SM-A155F", NOW, null, null, null,
                true);
    }

    private double count(String type, String channel, String outcome) {
        Counter c = meters.find("device_security.notifications")
                .tag("type", type).tag("channel", channel).tag("outcome", outcome).counter();
        return c == null ? 0.0 : c.count();
    }

    @Test
    @DisplayName("PHONE_REPLACED goes out on SMS and then WhatsApp, even though the customer prefers WhatsApp")
    void phoneReplaced_goesOutOnEveryChannel() {
        notifier.deliverNotice(phoneReplaced(ZW_NUMBER));

        InOrder order = inOrder(sms, whatsApp);
        order.verify(sms).sendSms(eq(ZW_NUMBER), eq(REPLACED_SMS), startsWith("DTX-PHONE_REPLACED-"));
        order.verify(whatsApp).sendCustomNotification(ZW_NUMBER, REPLACED_WHATSAPP);
        verifyNoMoreInteractions(sms, whatsApp);
        assertThat(count("PHONE_REPLACED", "SMS", "sent")).isEqualTo(1.0);
        assertThat(count("PHONE_REPLACED", "WHATSAPP", "sent")).isEqualTo(1.0);
        assertThat(count("PHONE_REPLACED", "all", "undelivered")).isZero();
    }

    @Test
    @DisplayName("PHONE_REPLACED: a failing SMS gateway does not stop the WhatsApp copy, and the notice counts as delivered")
    void phoneReplaced_oneChannelFailing_stillTriesTheOther() {
        doThrow(new NotificationDeliveryException("SMS gateway down"))
                .when(sms).sendSms(anyString(), anyString(), anyString());

        notifier.deliverNotice(phoneReplaced(ZW_NUMBER));

        InOrder order = inOrder(sms, whatsApp);
        order.verify(sms).sendSms(eq(ZW_NUMBER), eq(REPLACED_SMS), startsWith("DTX-PHONE_REPLACED-"));
        order.verify(whatsApp).sendCustomNotification(ZW_NUMBER, REPLACED_WHATSAPP);
        assertThat(count("PHONE_REPLACED", "SMS", "failed")).isEqualTo(1.0);
        assertThat(count("PHONE_REPLACED", "SMS", "sent")).isZero();
        assertThat(count("PHONE_REPLACED", "WHATSAPP", "sent")).isEqualTo(1.0);
        assertThat(count("PHONE_REPLACED", "all", "undelivered")).isZero();
    }

    @Test
    @DisplayName("PHONE_REPLACED: a failing WhatsApp after a delivered SMS is a failed channel, not an undelivered notice")
    void phoneReplaced_secondChannelFailing_isNotUndelivered() {
        doThrow(new NotificationDeliveryException("WhatsApp gateway down"))
                .when(whatsApp).sendCustomNotification(anyString(), anyString());

        notifier.deliverNotice(phoneReplaced(ZW_NUMBER));

        verify(sms).sendSms(eq(ZW_NUMBER), eq(REPLACED_SMS), startsWith("DTX-PHONE_REPLACED-"));
        verify(whatsApp).sendCustomNotification(ZW_NUMBER, REPLACED_WHATSAPP);
        assertThat(count("PHONE_REPLACED", "SMS", "sent")).isEqualTo(1.0);
        assertThat(count("PHONE_REPLACED", "WHATSAPP", "failed")).isEqualTo(1.0);
        assertThat(count("PHONE_REPLACED", "all", "undelivered")).isZero();
    }

    @Test
    @DisplayName("PHONE_REPLACED: when every channel fails, each failure is counted and the notice is undelivered once")
    void phoneReplaced_allFailing_countsUndelivered() {
        doThrow(new NotificationDeliveryException("SMS gateway down"))
                .when(sms).sendSms(anyString(), anyString(), anyString());
        doThrow(new NotificationDeliveryException("WhatsApp gateway down"))
                .when(whatsApp).sendCustomNotification(anyString(), anyString());

        notifier.deliverNotice(phoneReplaced(ZW_NUMBER));

        verify(sms).sendSms(eq(ZW_NUMBER), eq(REPLACED_SMS), startsWith("DTX-PHONE_REPLACED-"));
        verify(whatsApp).sendCustomNotification(ZW_NUMBER, REPLACED_WHATSAPP);
        assertThat(count("PHONE_REPLACED", "SMS", "failed")).isEqualTo(1.0);
        assertThat(count("PHONE_REPLACED", "WHATSAPP", "failed")).isEqualTo(1.0);
        assertThat(count("PHONE_REPLACED", "all", "undelivered")).isEqualTo(1.0);
        assertThat(count("PHONE_REPLACED", "SMS", "sent")).isZero();
        assertThat(count("PHONE_REPLACED", "WHATSAPP", "sent")).isZero();
    }

    @Test
    @DisplayName("every other notice stops at the first channel that delivers: NEW_DEVICE_BOUND to a WhatsApp customer "
            + "is one WhatsApp message and no SMS")
    void otherNotices_stopAtTheFirstChannel() {
        // As DeviceEnforcement.bind publishes it: the profile's preferred channel, not SMS first.
        notifier.deliverNotice(new DeviceNotice(DeviceNotice.Type.NEW_DEVICE_BOUND, ZW_NUMBER, "Samsung SM-A155F", NOW,
                null, null, OtpChannel.WHATSAPP, false));

        verify(whatsApp).sendCustomNotification(ZW_NUMBER,
                "A new phone (Samsung SM-A155F) signed in to your InnBucks at 09:58. If this wasn't you, dial *569# now.");
        verify(sms, never()).sendSms(anyString(), anyString(), anyString());
        assertThat(count("NEW_DEVICE_BOUND", "WHATSAPP", "sent")).isEqualTo(1.0);
        assertThat(count("NEW_DEVICE_BOUND", "SMS", "sent")).isZero();

        // And every other type the same way: one message each, on the first channel only.
        int others = 0;
        for (DeviceNotice.Type type : DeviceNotice.Type.values()) {
            if (type == DeviceNotice.Type.PHONE_REPLACED || type == DeviceNotice.Type.NEW_DEVICE_BOUND) continue;
            notifier.deliverNotice(new DeviceNotice(type, ZW_NUMBER, "Samsung SM-A155F", NOW, NOW.plusMinutes(32),
                    "SEC-8F2KQ7", null, false));
            others++;
            assertThat(count(type.name(), "WHATSAPP", "sent")).as(type.name()).isEqualTo(1.0);
        }
        verify(whatsApp, times(others + 1)).sendCustomNotification(eq(ZW_NUMBER), anyString());
        verifyNoInteractions(sms);
    }

    @Test
    @DisplayName("PHONE_REPLACED to a foreign number is WhatsApp only: this cell's SMS gateway cannot reach it")
    void phoneReplaced_foreignNumber_isWhatsAppOnly() {
        notifier.deliverNotice(phoneReplaced(ZA_NUMBER));

        verify(whatsApp).sendCustomNotification(ZA_NUMBER, REPLACED_WHATSAPP);
        verifyNoInteractions(sms);
        assertThat(count("PHONE_REPLACED", "WHATSAPP", "sent")).isEqualTo(1.0);
        assertThat(count("PHONE_REPLACED", "SMS", "sent")).isZero();
        assertThat(count("PHONE_REPLACED", "SMS", "failed")).isZero();
        assertThat(count("PHONE_REPLACED", "all", "undelivered")).isZero();
    }
}
