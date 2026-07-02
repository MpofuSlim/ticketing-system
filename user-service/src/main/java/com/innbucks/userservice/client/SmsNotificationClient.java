package com.innbucks.userservice.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Sends SMS through the InnBucks public notification API
 * ({@code POST /api/notification/sms}) — the SAME authenticated gateway the
 * email client uses (an {@code X-Api-Key} header plus a bearer obtained from
 * {@code POST /auth/third-party}).
 *
 * <p>Previously this posted to the separate {@code innbucks-core-gateway}
 * messenger bridge (unauthenticated, {@code /notifications/sms}); that path was
 * unreliable while email — on the notification API — worked. SMS now rides the
 * same proven gateway. The auth/token machinery lives in
 * {@link EmailNotificationClient} (the notification-API client), so this
 * delegates to it and shares its cached bearer.
 *
 * <p>Kept as a distinct bean so its callers (OTP, credential delivery, account
 * security alerts) are unchanged. Failures surface as
 * {@link NotificationDeliveryException} so callers keep best-effort / fallback
 * semantics. Never logs the message body — it may contain an OTP or password.
 */
@Slf4j
@Component
public class SmsNotificationClient {

    private final EmailNotificationClient notificationApi;

    public SmsNotificationClient(EmailNotificationClient notificationApi) {
        this.notificationApi = notificationApi;
    }

    /**
     * Dispatch an SMS to {@code destination} (E.164, e.g. {@code +263771234567}).
     * Delegates to the shared notification-API client
     * ({@link EmailNotificationClient#sendSms}); a non-2xx or connectivity
     * failure becomes a {@link NotificationDeliveryException} so the caller can
     * fall back to another channel.
     */
    public void sendSms(String destination, String message, String reference) {
        notificationApi.sendSms(destination, message, reference);
    }
}
