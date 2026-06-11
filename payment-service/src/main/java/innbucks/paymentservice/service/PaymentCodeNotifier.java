package innbucks.paymentservice.service;

import innbucks.paymentservice.client.SmsNotificationClient;
import innbucks.paymentservice.client.WhatsAppNotificationClient;
import innbucks.paymentservice.util.MsisdnMasking;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Locale;

/**
 * Delivers the InnBucks payment code to the customer — the link that makes
 * the 2D-code flow work with ZERO FE changes: the FE still posts only
 * {@code bookingId}; the code reaches the customer out-of-band on the phone
 * number captured at booking time, and they approve it in their own InnBucks
 * app (or USSD).
 *
 * <p>Channel order: <b>WhatsApp primary → SMS fallback</b> — same ordering as
 * booking-service's confirmation notifications (WhatsApp carries longer copy;
 * SMS catches everyone else). Best-effort by design: a delivery failure never
 * fails the payment — the code stays valid and is also echoed on the
 * {@code POST /payments} response ({@code paymentCode}), and an undelivered
 * code simply expires, freeing the booking slot for a clean retry.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentCodeNotifier {

    /** Delivery outcome — journaled onto the payment's event trail. */
    public enum Delivery { WHATSAPP, SMS, FAILED }

    private final WhatsAppNotificationClient whatsApp;
    private final SmsNotificationClient sms;

    public Delivery sendPaymentCode(String msisdn, String code, BigDecimal amount,
                                    String currency, Duration ttl, String paymentReference) {
        String whatsAppMessage = buildWhatsAppMessage(code, amount, currency, ttl, paymentReference);
        try {
            whatsApp.sendCustomNotification(msisdn, whatsAppMessage);
            log.info("Payment-code WhatsApp sent msisdn={} paymentReference={}",
                    MsisdnMasking.mask(msisdn), paymentReference);
            return Delivery.WHATSAPP;
        } catch (RuntimeException waEx) {
            log.warn("Payment-code WhatsApp failed msisdn={} paymentReference={}, trying SMS: {}",
                    MsisdnMasking.mask(msisdn), paymentReference, waEx.getMessage());
        }
        try {
            sms.sendSms(msisdn, buildSmsMessage(code, amount, currency, ttl), paymentReference);
            log.info("Payment-code SMS sent msisdn={} paymentReference={}",
                    MsisdnMasking.mask(msisdn), paymentReference);
            return Delivery.SMS;
        } catch (RuntimeException smsEx) {
            log.error("Payment-code delivery FAILED on both channels msisdn={} paymentReference={}: {}",
                    MsisdnMasking.mask(msisdn), paymentReference, smsEx.getMessage());
            return Delivery.FAILED;
        }
    }

    private static String buildWhatsAppMessage(String code, BigDecimal amount, String currency,
                                               Duration ttl, String paymentReference) {
        return "Your InnBucks payment code is " + code + ".\n\n"
                + "Open the InnBucks app, choose Pay by Code, and approve "
                + formatAmount(amount, currency) + " to complete your ticket booking.\n\n"
                + "The code expires in " + ttl.toMinutes() + " minutes. Ref: " + paymentReference;
    }

    /** Kept under 160 chars so it ships as a single SMS segment. */
    private static String buildSmsMessage(String code, BigDecimal amount, String currency, Duration ttl) {
        return "InnBucks code " + code + ": approve " + formatAmount(amount, currency)
                + " in your InnBucks app (Pay by Code) to complete your ticket booking. Expires in "
                + ttl.toMinutes() + " min.";
    }

    private static String formatAmount(BigDecimal amount, String currency) {
        return String.format(Locale.ROOT, "%s %.2f", currency, amount);
    }
}
