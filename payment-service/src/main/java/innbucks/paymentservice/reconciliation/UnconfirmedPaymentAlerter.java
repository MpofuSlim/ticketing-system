package innbucks.paymentservice.reconciliation;

import innbucks.paymentservice.client.EmailNotificationClient;
import innbucks.paymentservice.client.WhatsAppNotificationClient;
import innbucks.paymentservice.entity.Payment;
import innbucks.paymentservice.repository.PaymentRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * One-time human escalation for the paid-but-unconfirmable queue. When the
 * reconciler's confirm retry keeps failing for a {@code COMPLETED_UNCONFIRMED}
 * payment — money taken, booking not confirmable, no automatic reversal on the
 * code rail — a metric alone leaves both the operator and the customer in the
 * dark. On the FIRST still-failing retry per payment this sends:
 *
 * <ul>
 *   <li>an operator email to {@code payment.operator-alert-email}
 *       ({@code OPERATOR_ALERT_EMAIL}; blank = disabled) with the row's
 *       identifiers so it can be worked as a queue item, and</li>
 *   <li>a customer WhatsApp reassurance ("payment received, we're confirming
 *       your booking manually — no action needed") so silence doesn't turn
 *       into a chargeback call.</li>
 * </ul>
 *
 * <p>At-most-once: {@code payment.operator_alerted_at} (V11) is stamped BEFORE
 * the sends, so even a crash mid-send can only under-alert, never spam the
 * nightly sweep. Strictly best-effort — any delivery failure is a log line;
 * the metric {@code payment.payments.unconfirmed_retry{outcome=still_failing}}
 * stays the dashboard signal regardless.
 */
@Component
@Slf4j
public class UnconfirmedPaymentAlerter {

    private final EmailNotificationClient email;
    private final WhatsAppNotificationClient whatsApp;
    private final PaymentRepository payments;
    private final String operatorEmail;

    public UnconfirmedPaymentAlerter(EmailNotificationClient email,
                                     WhatsAppNotificationClient whatsApp,
                                     PaymentRepository payments,
                                     @Value("${payment.operator-alert-email:}") String operatorEmail) {
        this.email = email;
        this.whatsApp = whatsApp;
        this.payments = payments;
        this.operatorEmail = operatorEmail == null ? "" : operatorEmail.trim();
    }

    /** Called by the reconciler each time a confirm retry still fails. */
    public void onStillFailing(Payment payment, String reason) {
        if (payment.getOperatorAlertedAt() != null) {
            return; // already escalated once — the queue item is with a human
        }
        payment.setOperatorAlertedAt(Instant.now());
        payments.save(payment);

        alertOperator(payment, reason);
        reassureCustomer(payment);
    }

    private void alertOperator(Payment p, String reason) {
        if (operatorEmail.isBlank()) {
            log.info("Stuck payment paymentId={} — no OPERATOR_ALERT_EMAIL configured, operator email skipped",
                    p.getId());
            return;
        }
        String subject = "Stuck payment needs manual confirmation: " + p.getPaymentReference();
        String body = "A paid booking cannot be auto-confirmed and needs operator attention.\n\n"
                + "Payment reference: " + p.getPaymentReference() + "\n"
                + "Payment id:        " + p.getId() + "\n"
                + "Booking id:        " + p.getBookingId() + "\n"
                + "Amount:            " + p.getCurrency() + " " + p.getAmount() + "\n"
                + "Customer msisdn:   " + p.getCustomerMsisdn() + "\n"
                + "Last failure:      " + reason + "\n\n"
                + "The customer HAS paid (status COMPLETED_UNCONFIRMED); real-time reversal "
                + "is not available on the code rail. Confirm the booking manually or contact "
                + "the customer. The reconciler keeps retrying automatically; this alert is "
                + "sent once per payment.";
        try {
            email.sendEmail(operatorEmail, subject, body, "PAY-OPS-" + p.getId());
            log.info("Operator alerted for stuck payment paymentId={} -> {}", p.getId(), operatorEmail);
        } catch (RuntimeException e) {
            log.warn("Operator alert email failed paymentId={} (metric still fires): {}",
                    p.getId(), e.getMessage());
        }
    }

    private void reassureCustomer(Payment p) {
        String msisdn = p.getCustomerMsisdn();
        if (msisdn == null || msisdn.isBlank()) {
            return;
        }
        String message = "We received your InnBucks payment " + p.getPaymentReference()
                + " and are confirming your booking manually. No action is needed — "
                + "you will get your confirmation shortly. Your payment is safe.";
        try {
            whatsApp.sendCustomNotification(msisdn, message);
            log.info("Customer reassured about stuck payment paymentId={}", p.getId());
        } catch (RuntimeException e) {
            log.warn("Customer reassurance WhatsApp failed paymentId={}: {}", p.getId(), e.getMessage());
        }
    }
}
