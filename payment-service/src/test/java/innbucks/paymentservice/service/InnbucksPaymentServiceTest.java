package innbucks.paymentservice.service;

import innbucks.paymentservice.client.BookingServiceClient;
import innbucks.paymentservice.client.BookingServiceClient.BookingConfirmationException;
import innbucks.paymentservice.client.InnbucksCoreGatewayClient;
import innbucks.paymentservice.client.InnbucksCoreGatewayResponse;
import innbucks.paymentservice.client.InnbucksCoreGatewayTransientException;
import innbucks.paymentservice.client.OradianMiddlewareClient;
import innbucks.paymentservice.client.PaymentOutcome;
import innbucks.paymentservice.dto.DepositAccount;
import innbucks.paymentservice.dto.InnbucksPaymentResponse;
import innbucks.paymentservice.dto.InnbucksPaymentResponse.Status;
import innbucks.paymentservice.entity.Payment;
import innbucks.paymentservice.service.InnbucksPaymentService.InvalidPaymentRequestException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
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
    private InnbucksCoreGatewayClient gateway;
    private OradianMiddlewareClient oradian;
    private BookingServiceClient bookings;
    private InnbucksPaymentService service;

    private final UUID bookingId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        records = mock(PaymentRecordService.class);
        gateway = mock(InnbucksCoreGatewayClient.class);
        oradian = mock(OradianMiddlewareClient.class);
        bookings = mock(BookingServiceClient.class);
        service = new InnbucksPaymentService(records, gateway, oradian, bookings);
        ReflectionTestUtils.setField(service, "merchantAccount", "MERCH-1");
        ReflectionTestUtils.setField(service, "participantId", "");

        lenient().when(records.hasActiveOrSucceededPayment(any())).thenReturn(false);
        lenient().when(oradian.getDepositsForMsisdn(anyString())).thenReturn(List.of(mainWallet()));
        lenient().when(bookings.getBooking(bookingId)).thenReturn(Map.of(
                "totalAmount", new BigDecimal("50.00"), "currency", "USD"));
        lenient().when(records.openPending(any(Payment.class))).thenAnswer(inv -> {
            Payment p = inv.getArgument(0);
            p.setId(UUID.randomUUID());
            p.setStatus(Payment.PaymentStatus.PENDING);
            return p;
        });
    }

    private static DepositAccount mainWallet() {
        return DepositAccount.builder()
                .internalID("CUST-ACC-1").isMainAccount("true").status("Active").build();
    }

    private static InnbucksCoreGatewayResponse response(PaymentOutcome outcome, String ref) {
        return new InnbucksCoreGatewayResponse("TKT-PMT-x", outcome, ref, null, null, null);
    }

    @Test
    void activePaymentExists_rejected409_beforeAnyUpstreamCall() {
        when(records.hasActiveOrSucceededPayment(bookingId)).thenReturn(true);

        InvalidPaymentRequestException ex = assertThrows(InvalidPaymentRequestException.class,
                () -> service.processPayment(bookingId, "+263770000001", "idem-1"));

        assertEquals(409, ex.getStatusCode());
        verifyNoInteractions(oradian, gateway);
        verify(records, never()).openPending(any());
    }

    @Test
    void raceLoserOnUniqueIndex_mapsTo409() {
        when(records.openPending(any(Payment.class)))
                .thenThrow(new DataIntegrityViolationException("uq_payment_active_booking"));

        InvalidPaymentRequestException ex = assertThrows(InvalidPaymentRequestException.class,
                () -> service.processPayment(bookingId, "+263770000001", "idem-1"));

        assertEquals(409, ex.getStatusCode());
        verifyNoInteractions(gateway);
    }

    @Test
    void gatewayTransient_marksInDoubt_returnsProcessing() {
        when(gateway.debit(any(), anyString()))
                .thenThrow(new InnbucksCoreGatewayTransientException("gateway 503", 503));

        InnbucksPaymentResponse resp = service.processPayment(bookingId, "+263770000001", "idem-1");

        assertEquals(Status.PROCESSING, resp.getStatus());
        verify(records).markInDoubt(any(UUID.class), contains("503"));
        verify(records, never()).markFailed(any(), anyString(), anyString());
    }

    @Test
    void nullOutcome_marksInDoubt_returnsProcessing() {
        when(gateway.debit(any(), anyString())).thenReturn(response(null, "VEENGU-REF-1"));

        InnbucksPaymentResponse resp = service.processPayment(bookingId, "+263770000001", "idem-1");

        assertEquals(Status.PROCESSING, resp.getStatus());
        verify(records).markInDoubt(any(UUID.class), contains("VEENGU-REF-1"));
    }

    @Test
    void upstreamUnavailable_marksInDoubt_returnsProcessing() {
        when(gateway.debit(any(), anyString()))
                .thenReturn(response(PaymentOutcome.UPSTREAM_UNAVAILABLE, null));

        InnbucksPaymentResponse resp = service.processPayment(bookingId, "+263770000001", "idem-1");

        assertEquals(Status.PROCESSING, resp.getStatus());
        verify(records).markInDoubt(any(UUID.class), anyString());
    }

    @Test
    void completed_confirmFails_marksCompletedUnconfirmed_notFailed_andReturnsProcessing() {
        when(gateway.debit(any(), anyString()))
                .thenReturn(response(PaymentOutcome.COMPLETED, "VEENGU-REF-9"));
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
        when(gateway.debit(any(), anyString()))
                .thenReturn(response(PaymentOutcome.COMPLETED, "VEENGU-REF-2"));
        when(bookings.confirmBooking(bookingId))
                .thenReturn(Map.of("confirmationNumber", "INN-CONF-2"));

        InnbucksPaymentResponse resp = service.processPayment(bookingId, "+263770000001", "idem-1");

        assertEquals(Status.SUCCESS, resp.getStatus());
        verify(records).markSucceeded(any(UUID.class), eq("VEENGU-REF-2"), eq("INN-CONF-2"));
    }

    @Test
    void rejectedOutcome_marksFailed() {
        InnbucksCoreGatewayResponse rejected = new InnbucksCoreGatewayResponse(
                "TKT-PMT-x", PaymentOutcome.REJECTED_INSUFFICIENT_FUNDS, null,
                "NOT_SUFFICIENT_FUNDS", "balance too low", null);
        when(gateway.debit(any(), anyString())).thenReturn(rejected);

        InnbucksPaymentResponse resp = service.processPayment(bookingId, "+263770000001", "idem-1");

        assertEquals(Status.FAILED, resp.getStatus());
        verify(records).markFailed(any(UUID.class), eq("NOT_SUFFICIENT_FUNDS"), eq("balance too low"));
        verify(records, never()).markInDoubt(any(), anyString());
    }
}
