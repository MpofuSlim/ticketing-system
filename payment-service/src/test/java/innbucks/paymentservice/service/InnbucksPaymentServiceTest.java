package innbucks.paymentservice.service;

import innbucks.paymentservice.client.BankApiClient;
import innbucks.paymentservice.client.BankApiTransientException;
import innbucks.paymentservice.client.BankPaymentCommand;
import innbucks.paymentservice.client.BankPaymentResult;
import innbucks.paymentservice.client.BookingServiceClient;
import innbucks.paymentservice.client.BookingServiceClient.BookingConfirmationException;
import innbucks.paymentservice.client.PaymentOutcome;
import innbucks.paymentservice.dto.InnbucksPaymentResponse;
import innbucks.paymentservice.dto.InnbucksPaymentResponse.Status;
import innbucks.paymentservice.entity.Payment;
import innbucks.paymentservice.service.InnbucksPaymentService.InvalidPaymentRequestException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Pins the ledger discipline added in the hardening pass:
 * <ul>
 *   <li>timeout / unclassifiable outcomes are recorded IN_DOUBT — never
 *       FAILED, never silently left PENDING;</li>
 *   <li>a confirmed debit whose booking confirm fails is recorded
 *       COMPLETED_UNCONFIRMED (money moved!) with the veengu reference, and
 *       the customer-facing status is PROCESSING;</li>
 *   <li>one active payment per booking: pre-check 409 + race-loser 409 on
 *       the unique-index violation.</li>
 * </ul>
 */
class InnbucksPaymentServiceTest {

    private PaymentRecordService records;
    private BankApiClient bankApi;
    private BookingServiceClient bookings;
    private InnbucksPaymentService service;

    private final UUID bookingId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        records = mock(PaymentRecordService.class);
        bankApi = mock(BankApiClient.class);
        bookings = mock(BookingServiceClient.class);
        service = new InnbucksPaymentService(records, bankApi, bookings);
        ReflectionTestUtils.setField(service, "merchantAccount", "MERCH-1");

        lenient().when(records.hasActiveOrSucceededPayment(any())).thenReturn(false);
        lenient().when(bankApi.findWalletAccount(anyString())).thenReturn(java.util.Optional.of("CUST-ACC-1"));
        lenient().when(bookings.getBooking(bookingId)).thenReturn(Map.of(
                "totalAmount", new BigDecimal("50.00"), "currency", "USD"));
        lenient().when(records.openPending(any(Payment.class))).thenAnswer(inv -> {
            Payment p = inv.getArgument(0);
            p.setId(UUID.randomUUID());
            p.setStatus(Payment.PaymentStatus.PENDING);
            return p;
        });
    }

    private static BankPaymentResult result(PaymentOutcome outcome, String ref) {
        return new BankPaymentResult(outcome, ref, null, null);
    }

    @Test
    void activePaymentExists_rejected409_beforeAnyUpstreamCall() {
        when(records.hasActiveOrSucceededPayment(bookingId)).thenReturn(true);

        InvalidPaymentRequestException ex = assertThrows(InvalidPaymentRequestException.class,
                () -> service.processPayment(bookingId, "+263770000001", "idem-1"));

        assertEquals(409, ex.getStatusCode());
        verifyNoInteractions(bankApi);
        verify(records, never()).openPending(any());
    }

    @Test
    void raceLoserOnUniqueIndex_mapsTo409() {
        when(records.openPending(any(Payment.class)))
                .thenThrow(new DataIntegrityViolationException("uq_payment_active_booking"));

        InvalidPaymentRequestException ex = assertThrows(InvalidPaymentRequestException.class,
                () -> service.processPayment(bookingId, "+263770000001", "idem-1"));

        assertEquals(409, ex.getStatusCode());
        verify(bankApi, never()).pay(any(BankPaymentCommand.class));
    }

    @Test
    void gatewayTransient_marksInDoubt_returnsProcessing() {
        when(bankApi.pay(any(BankPaymentCommand.class)))
                .thenThrow(new BankApiTransientException("bank 503", 503));

        InnbucksPaymentResponse resp = service.processPayment(bookingId, "+263770000001", "idem-1");

        assertEquals(Status.PROCESSING, resp.getStatus());
        verify(records).markInDoubt(any(UUID.class), contains("503"));
        verify(records, never()).markFailed(any(), anyString(), anyString());
    }

    @Test
    void nullOutcome_marksInDoubt_returnsProcessing() {
        when(bankApi.pay(any(BankPaymentCommand.class))).thenReturn(result(null, "VEENGU-REF-1"));

        InnbucksPaymentResponse resp = service.processPayment(bookingId, "+263770000001", "idem-1");

        assertEquals(Status.PROCESSING, resp.getStatus());
        verify(records).markInDoubt(any(UUID.class), contains("VEENGU-REF-1"));
    }

    @Test
    void upstreamUnavailable_marksInDoubt_returnsProcessing() {
        when(bankApi.pay(any(BankPaymentCommand.class)))
                .thenReturn(result(PaymentOutcome.UPSTREAM_UNAVAILABLE, null));

        InnbucksPaymentResponse resp = service.processPayment(bookingId, "+263770000001", "idem-1");

        assertEquals(Status.PROCESSING, resp.getStatus());
        verify(records).markInDoubt(any(UUID.class), anyString());
    }

    @Test
    void completed_confirmFails_marksCompletedUnconfirmed_notFailed_andReturnsProcessing() {
        when(bankApi.pay(any(BankPaymentCommand.class)))
                .thenReturn(result(PaymentOutcome.COMPLETED, "VEENGU-REF-9"));
        when(bookings.confirmBooking(bookingId))
                .thenThrow(new BookingConfirmationException("hold expired", 409));

        InnbucksPaymentResponse resp = service.processPayment(bookingId, "+263770000001", "idem-1");

        // Money moved: the ledger must say so, and the customer must NOT be
        // told the payment failed.
        verify(records).markCompletedUnconfirmed(any(UUID.class), eq("VEENGU-REF-9"), contains("hold expired"));
        verify(records, never()).markFailed(any(), anyString(), anyString());
        assertEquals(Status.PROCESSING, resp.getStatus());
        assertEquals("booking_confirm_pending", resp.getUpstreamCode());
    }

    @Test
    void completed_confirmSucceeds_marksSucceeded() {
        when(bankApi.pay(any(BankPaymentCommand.class)))
                .thenReturn(result(PaymentOutcome.COMPLETED, "VEENGU-REF-2"));
        when(bookings.confirmBooking(bookingId))
                .thenReturn(Map.of("confirmationNumber", "INN-CONF-2"));

        InnbucksPaymentResponse resp = service.processPayment(bookingId, "+263770000001", "idem-1");

        assertEquals(Status.SUCCESS, resp.getStatus());
        verify(records).markSucceeded(any(UUID.class), eq("VEENGU-REF-2"), eq("INN-CONF-2"));
    }

    @Test
    void rejectedOutcome_marksFailed() {
        BankPaymentResult rejected = new BankPaymentResult(
                PaymentOutcome.REJECTED_INSUFFICIENT_FUNDS, null,
                "NOT_SUFFICIENT_FUNDS", "balance too low");
        when(bankApi.pay(any(BankPaymentCommand.class))).thenReturn(rejected);

        InnbucksPaymentResponse resp = service.processPayment(bookingId, "+263770000001", "idem-1");

        assertEquals(Status.FAILED, resp.getStatus());
        verify(records).markFailed(any(UUID.class), eq("NOT_SUFFICIENT_FUNDS"), eq("balance too low"));
        verify(records, never()).markInDoubt(any(), anyString());
    }
}
