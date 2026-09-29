package com.innbucks.userservice.devicesecurity;

import com.innbucks.userservice.client.NotificationDeliveryException;
import com.innbucks.userservice.client.SmsNotificationClient;
import com.innbucks.userservice.client.WhatsAppNotificationClient;
import com.innbucks.userservice.devicesecurity.entity.CustomerSecurityProfile;
import com.innbucks.userservice.devicesecurity.repository.CustomerSecurityProfileRepository;
import com.innbucks.userservice.util.MsisdnCountryResolver;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Delivers {@link DeviceNotice}s (§10) and OTP codes.
 *
 * <p>Notices are subordinate to the change that caused them: delivered AFTER
 * COMMIT (nobody is told about a block that then rolled back), on the
 * device-security pool (a slow gateway adds no latency to a sign-in or an
 * unlock), and nothing escapes — an exception from an after-commit callback
 * would surface to the caller of commit and make a dead SMS gateway look like a
 * failed unlock. OTP codes are the opposite and are sent synchronously by
 * {@link #sendOtp}: the customer is waiting for them, and a failure must reach
 * the app as a 503 naming the channel so it can offer the other one (§5.2).
 */
@Component
@Slf4j
public class DeviceSecurityNotifier {

    private final WhatsAppNotificationClient whatsApp;
    private final SmsNotificationClient sms;
    private final DeviceSecurityMessages messages;
    private final DeviceSecurityMetrics metrics;
    private final CustomerSecurityProfileRepository profiles;
    private final Clock clock;
    private final String deploymentCountry;

    public DeviceSecurityNotifier(WhatsAppNotificationClient whatsApp, SmsNotificationClient sms,
                                  DeviceSecurityMessages messages, DeviceSecurityMetrics metrics,
                                  CustomerSecurityProfileRepository profiles, Clock deviceSecurityClock,
                                  @Value("${innbucks.country:ZW}") String deploymentCountry) {
        this.whatsApp = whatsApp;
        this.sms = sms;
        this.messages = messages;
        this.metrics = metrics;
        this.profiles = profiles;
        this.clock = deviceSecurityClock;
        this.deploymentCountry = deploymentCountry;
    }

    /**
     * Whether SMS can reach this number from this cell. The SMS gateway is
     * per-country and silently drops a foreign number after a 2xx, so a foreign
     * number is WhatsApp-only — the same rule OtpService applies.
     */
    public boolean smsReaches(String msisdn) {
        return MsisdnCountryResolver.resolve(msisdn)
                .map(c -> c.equalsIgnoreCase(deploymentCountry))
                .orElse(false);
    }

    /** Sends one OTP on exactly the channel asked for. Throws on failure; no silent fallback. */
    public void sendOtp(String msisdn, OtpChannel channel, String text, String reference) {
        deliver(msisdn, channel, text, reference);
    }

    @Async("deviceSecurityExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onNotice(DeviceNotice notice) {
        try {
            deliverNotice(notice);
        } catch (RuntimeException e) {
            // Nothing escapes: the change this notice describes has already committed.
            log.warn("Device-security notice {} to {} was not delivered: {}", notice.type(),
                    DeviceIdentity.logMask(notice.msisdn()), e.getMessage());
        }
    }

    void deliverNotice(DeviceNotice notice) {
        LocalDateTime now = LocalDateTime.now(clock);
        List<OtpChannel> order = order(notice);
        String reference = "DTX-" + notice.type().name() + "-" + System.currentTimeMillis();
        for (OtpChannel channel : order) {
            try {
                deliver(notice.msisdn(), channel, messages.notice(notice, channel, now), reference);
                metrics.notification(notice.type().name(), channel.name(), "sent");
                log.info("Device-security notice {} sent via {} to {}", notice.type(), channel,
                        DeviceIdentity.logMask(notice.msisdn()));
                return;
            } catch (NotificationDeliveryException e) {
                metrics.notification(notice.type().name(), channel.name(), "failed");
                log.warn("Device-security notice {} via {} failed for {}: {}", notice.type(), channel,
                        DeviceIdentity.logMask(notice.msisdn()), e.getMessage());
            }
        }
        metrics.notification(notice.type().name(), "all", "undelivered");
    }

    List<OtpChannel> order(DeviceNotice notice) {
        List<OtpChannel> order = new ArrayList<>();
        if (!smsReaches(notice.msisdn())) {
            order.add(OtpChannel.WHATSAPP);
            return order;
        }
        OtpChannel preferred = notice.preferredChannel() != null ? notice.preferredChannel()
                : profiles.findById(notice.msisdn()).map(CustomerSecurityProfile::getPreferredChannel).orElse(null);
        OtpChannel first = notice.smsFirst() ? OtpChannel.SMS : (preferred == null ? OtpChannel.SMS : preferred);
        order.add(first);
        order.add(first == OtpChannel.SMS ? OtpChannel.WHATSAPP : OtpChannel.SMS);
        return order;
    }

    private void deliver(String msisdn, OtpChannel channel, String text, String reference) {
        if (channel == OtpChannel.WHATSAPP) {
            whatsApp.sendCustomNotification(msisdn, text);
        } else {
            sms.sendSms(msisdn, text, reference);
        }
    }
}
