package innbucks.paymentservice.repository;

import innbucks.paymentservice.entity.Payment;
import innbucks.paymentservice.entity.Payment.PaymentStatus;
import innbucks.paymentservice.entity.PaymentEvent;
import innbucks.paymentservice.testsupport.PostgresIntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Proves the V5 ledger-hardening invariants on REAL Postgres (Flyway-applied
 * schema, the exact partial index production runs):
 * <ul>
 *   <li>{@code uq_payment_active_booking}: a second active (or successful)
 *       payment row for the same booking is refused BY THE DATABASE — the
 *       application pre-check is UX, this index is the truth;</li>
 *   <li>terminal failures (FAILED) free the slot so a customer can retry;</li>
 *   <li>the widened status vocabulary round-trips through the CHECK
 *       constraint;</li>
 *   <li>{@code payment_event} accepts journal rows referencing the payment.</li>
 * </ul>
 */
class PaymentLedgerIntegrityPostgresIT extends PostgresIntegrationTestBase {

    @Autowired
    private PaymentRepository payments;

    @Autowired
    private PaymentEventRepository events;

    private static Payment row(UUID bookingId, PaymentStatus status) {
        return Payment.builder()
                .id(UUID.randomUUID())
                .paymentReference("TKT-PMT-" + UUID.randomUUID())
                .bookingId(bookingId)
                .customerMsisdn("+263770000001")
                .customerAccount("CUST-1")
                .merchantAccount("MERCH-1")
                .amount(new BigDecimal("50.00"))
                .currency("USD")
                .status(status)
                .build();
    }

    @Test
    void secondActivePaymentForSameBooking_isRefusedByTheIndex() {
        UUID bookingId = UUID.randomUUID();
        payments.saveAndFlush(row(bookingId, PaymentStatus.PENDING));

        assertThrows(DataIntegrityViolationException.class,
                () -> payments.saveAndFlush(row(bookingId, PaymentStatus.PENDING)),
                "two concurrent active payments for one booking must be impossible at the DB level");
    }

    @Test
    void succeededPayment_blocksAnyFurtherPaymentForTheBooking() {
        UUID bookingId = UUID.randomUUID();
        payments.saveAndFlush(row(bookingId, PaymentStatus.SUCCEEDED));

        assertThrows(DataIntegrityViolationException.class,
                () -> payments.saveAndFlush(row(bookingId, PaymentStatus.PENDING)),
                "an already-paid booking must never accept another payment row");
    }

    @Test
    void inDoubtPayment_holdsTheSlot() {
        // IN_DOUBT means money MAY have moved — a retry payment before the
        // doubt is resolved risks a double debit, so the slot stays occupied.
        UUID bookingId = UUID.randomUUID();
        payments.saveAndFlush(row(bookingId, PaymentStatus.IN_DOUBT));

        assertThrows(DataIntegrityViolationException.class,
                () -> payments.saveAndFlush(row(bookingId, PaymentStatus.PENDING)));
    }

    @Test
    void terminalFailure_freesTheSlotForARetry() {
        UUID bookingId = UUID.randomUUID();
        payments.saveAndFlush(row(bookingId, PaymentStatus.FAILED));

        assertDoesNotThrow(() -> payments.saveAndFlush(row(bookingId, PaymentStatus.PENDING)),
                "a declined payment must not lock the customer out of retrying");
    }

    @Test
    void newStatusVocabulary_roundTripsThroughTheCheckConstraint() {
        UUID bookingId = UUID.randomUUID();
        Payment p = payments.saveAndFlush(row(bookingId, PaymentStatus.COMPLETED_UNCONFIRMED));

        Payment reloaded = payments.findById(p.getId()).orElseThrow();
        assertEquals(PaymentStatus.COMPLETED_UNCONFIRMED, reloaded.getStatus());
    }

    @Test
    void tokenIssuedCode_holdsTheBookingSlot() {
        // An open InnBucks code IS an active payment: a second code for the
        // same booking would let the customer pay twice.
        UUID bookingId = UUID.randomUUID();
        payments.saveAndFlush(row(bookingId, PaymentStatus.TOKEN_ISSUED));

        assertThrows(DataIntegrityViolationException.class,
                () -> payments.saveAndFlush(row(bookingId, PaymentStatus.PENDING)));
    }

    @Test
    void expiredCode_freesTheSlot_forAFreshCode() {
        UUID bookingId = UUID.randomUUID();
        payments.saveAndFlush(row(bookingId, PaymentStatus.EXPIRED));

        assertDoesNotThrow(() -> payments.saveAndFlush(row(bookingId, PaymentStatus.PENDING)),
                "a lapsed code must not lock the customer out of paying again");
    }

    @Test
    void codeColumns_roundTripThroughV6() {
        // V6 columns + the relaxed account nullability (the code flow stores
        // neither customer_account nor merchant_account).
        java.time.Instant expiresAt = java.time.Instant.now().plusSeconds(600)
                .truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        Payment p = Payment.builder()
                .id(UUID.randomUUID())
                .paymentReference("TKT-PMT-" + UUID.randomUUID())
                .bookingId(UUID.randomUUID())
                .customerMsisdn("+263770000001")
                .amount(new BigDecimal("50.00"))
                .currency("USD")
                .status(PaymentStatus.TOKEN_ISSUED)
                .innbucksCode("701285660")
                .codeAuthNumber("1616800")
                .codeQrBase64("aGVsbG8=")
                .codeExpiresAt(expiresAt)
                .build();

        Payment reloaded = payments.findById(payments.saveAndFlush(p).getId()).orElseThrow();
        assertEquals("701285660", reloaded.getInnbucksCode());
        assertEquals("1616800", reloaded.getCodeAuthNumber());
        assertEquals("aGVsbG8=", reloaded.getCodeQrBase64());
        assertEquals(expiresAt, reloaded.getCodeExpiresAt());
        assertNull(reloaded.getCustomerAccount());
        assertNull(reloaded.getMerchantAccount());
    }

    @Test
    void paymentEventJournal_acceptsTransitionRows() {
        Payment p = payments.saveAndFlush(row(UUID.randomUUID(), PaymentStatus.PENDING));

        PaymentEvent opening = events.saveAndFlush(PaymentEvent.builder()
                .paymentId(p.getId())
                .fromStatus(null)
                .toStatus("PENDING")
                .detail("Ledger row opened before upstream call")
                .build());

        PaymentEvent reloaded = events.findById(opening.getId()).orElseThrow();
        assertNull(reloaded.getFromStatus());
        assertEquals("PENDING", reloaded.getToStatus());
        assertEquals(1, events.findByPaymentIdOrderByCreatedAtAsc(p.getId()).size());
    }
}
