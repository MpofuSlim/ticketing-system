package innbucks.paymentservice.service;

import innbucks.paymentservice.client.BookingServiceClient;
import innbucks.paymentservice.config.PaymentMetrics;
import innbucks.paymentservice.entity.Payment;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Terminal-state transitions for an open 2D-code payment, shared by the TWO
 * places a code can resolve: the 20s reconciliation poller and the instant
 * check a customer triggers by re-POSTing /payments ("I've paid"). One
 * implementation so the money rules can never drift between the two paths.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CodePaymentResolutionService {

    private final PaymentRecordService paymentRecordService;
    private final BookingServiceClient bookingServiceClient;
    private final PaymentMetrics metrics;

    /**
     * InnBucks says the customer paid (Paid, or Claimed — the doc defines
     * both as "finalised by the customer"). Confirm the booking, then promote;
     * a confirm failure parks the row COMPLETED_UNCONFIRMED with the
     * authNumber as the recovery handle — money HAS moved, and the
     * confirm-retry sweep keeps trying.
     */
    public void completePaid(Payment p, String rawUpstreamStatus) {
        try {
            Map<String, Object> confirmed = bookingServiceClient.confirmBooking(p.getBookingId());
            Object confirmation = confirmed == null ? null : confirmed.get("confirmationNumber");
            paymentRecordService.markSucceeded(p.getId(), p.getCodeAuthNumber(),
                    confirmation == null ? null : confirmation.toString());
            metrics.incCodeResolution("paid");
            log.info("Code paid + booking confirmed paymentReference={} bookingId={} confirmation={} upstreamStatus={}",
                    p.getPaymentReference(), p.getBookingId(), confirmation, rawUpstreamStatus);
        } catch (RuntimeException e) {
            paymentRecordService.markCompletedUnconfirmed(p.getId(), p.getCodeAuthNumber(),
                    "Customer paid the InnBucks code but booking confirm failed: " + e.getMessage());
            metrics.incCodeResolution("paid_unconfirmed");
            log.error("CODE PAID BUT BOOKING CONFIRM FAILED — confirm-retry will keep trying "
                            + "paymentReference={} bookingId={} reason={}",
                    p.getPaymentReference(), p.getBookingId(), e.getMessage());
        }
    }

    /** InnBucks reports the code Expired / Timed Out — never paid; free the slot. */
    public void markExpiredUpstream(Payment p, String rawUpstreamStatus) {
        paymentRecordService.markExpired(p.getId(),
                "InnBucks reports the code " + rawUpstreamStatus + " — never paid");
        metrics.incCodeResolution("expired");
        log.info("Code expired upstream paymentReference={} bookingId={} — slot freed",
                p.getPaymentReference(), p.getBookingId());
    }
}
