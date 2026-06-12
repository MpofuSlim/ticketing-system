package innbucks.paymentservice.service;

import innbucks.paymentservice.client.BookingServiceClient;
import innbucks.paymentservice.client.CodeGenerationResult;
import innbucks.paymentservice.client.InnbucksApiClient;
import innbucks.paymentservice.client.InnbucksApiException;
import innbucks.paymentservice.client.InnbucksApiTransientException;
import innbucks.paymentservice.config.PaymentMetrics;
import innbucks.paymentservice.dto.InnbucksPaymentResponse;
import innbucks.paymentservice.dto.InnbucksPaymentResponse.Status;
import innbucks.paymentservice.entity.Payment;
import innbucks.paymentservice.service.InnbucksPaymentService.InvalidPaymentRequestException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Pins the 2D-code flow's ledger + safety discipline:
 * <ul>
 *   <li>happy path: PENDING → TOKEN_ISSUED with the code handles, the code
 *       is DELIVERED (WhatsApp/SMS) and echoed on the PROCESSING response —
 *       zero FE involvement;</li>
 *   <li>amounts convert to CENTS exactly (sub-cent precision refused) and a
 *       mismatched amount echo kills the payment BEFORE the code reaches the
 *       customer — the 100x guard;</li>
 *   <li>generation failures (refusal/transient) close the row FAILED — no
 *       money moves on generate, the slot frees for a clean retry, and
 *       IN_DOUBT never arises in this flow;</li>
 *   <li>one active payment per booking: pre-check 409 + race-loser 409 on
 *       the unique-index violation;</li>
 *   <li>delivery failure is best-effort: journaled, never fails the payment.</li>
 * </ul>
 */
class InnbucksPaymentServiceTest {

    private PaymentRecordService records;
    private InnbucksApiClient innbucksApi;
    private BookingServiceClient bookings;
    private PaymentCodeNotifier notifier;
    private InnbucksPaymentService service;

    private final UUID bookingId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        records = mock(PaymentRecordService.class);
        innbucksApi = mock(InnbucksApiClient.class);
        bookings = mock(BookingServiceClient.class);
        notifier = mock(PaymentCodeNotifier.class);
        service = new InnbucksPaymentService(records, innbucksApi, bookings, notifier,
                new PaymentMetrics(new SimpleMeterRegistry()));
        ReflectionTestUtils.setField(service, "codeTtl", Duration.ofMinutes(10));

        lenient().when(records.hasActiveOrSucceededPayment(any())).thenReturn(false);
        lenient().when(bookings.getBooking(bookingId)).thenReturn(Map.of(
                "totalAmount", new BigDecimal("50.00"), "currency", "USD"));
        lenient().when(records.openPending(any(Payment.class))).thenAnswer(inv -> {
            Payment p = inv.getArgument(0);
            p.setId(UUID.randomUUID());
            p.setStatus(Payment.PaymentStatus.PENDING);
            return p;
        });
        lenient().when(notifier.sendPaymentCode(anyString(), anyString(), any(), anyString(), any(), anyString()))
                .thenReturn(PaymentCodeNotifier.Delivery.WHATSAPP);
    }

    private static CodeGenerationResult approved(String code, String authNumber, Long echoCents) {
        return new CodeGenerationResult(true, code, authNumber, "qr-base64-bytes", "414107", echoCents,
                "0", "Approved or completed successfully");
    }

    @Test
    void happyPath_issuesCode_marksTokenIssued_deliversAndEchoesIt() {
        when(innbucksApi.generatePaymentCode(anyString(), anyString(), eq(5000L)))
                .thenReturn(approved("701285660", "1616800", 5000L));

        InnbucksPaymentResponse resp = service.processPayment(bookingId, "+263770000001", null);

        assertEquals(Status.PROCESSING, resp.getStatus());
        assertEquals("701285660", resp.getPaymentCode());
        assertEquals("qr-base64-bytes", resp.getPaymentQrCode(),
                "the InnBucks QR must ride along for Scan-to-Pay");
        assertNotNull(resp.getPaymentCodeExpiresAt());
        // Ledger: code handles + QR + local deadline recorded on the transition.
        verify(records).markTokenIssued(any(UUID.class), eq("701285660"), eq("1616800"),
                eq("qr-base64-bytes"), any(Instant.class));
        // Delivery: the customer gets the code with the EXACT booking amount.
        verify(notifier).sendPaymentCode(eq("+263770000001"), eq("701285660"),
                eq(new BigDecimal("50.00")), eq("USD"), eq(Duration.ofMinutes(10)), anyString());
        verify(records).noteEvent(any(UUID.class), contains("WHATSAPP"));
        verify(records, never()).markFailed(any(), anyString(), anyString());
        verify(records, never()).markInDoubt(any(), anyString());
    }

    @Test
    void amountsConvertToCentsExactly() {
        // 50.00 USD == 5000 cents — the generation call must carry cents.
        when(innbucksApi.generatePaymentCode(anyString(), anyString(), anyLong()))
                .thenReturn(approved("701285660", "1616800", null));

        service.processPayment(bookingId, "+263770000001", null);

        verify(innbucksApi).generatePaymentCode(anyString(), anyString(), eq(5000L));
    }

    @Test
    void subCentPrecision_isRefused_beforeAnyUpstreamCall() {
        when(bookings.getBooking(bookingId)).thenReturn(Map.of(
                "totalAmount", new BigDecimal("50.005"), "currency", "USD"));

        InvalidPaymentRequestException ex = assertThrows(InvalidPaymentRequestException.class,
                () -> service.processPayment(bookingId, "+263770000001", null));

        assertEquals(422, ex.getStatusCode());
        verifyNoInteractions(innbucksApi);
    }

    @Test
    void amountEchoMismatch_failsRow_andNeverDeliversTheCode() {
        // We asked for 5000 cents; the platform echoed 50 — whatever the
        // cause (unit drift, truncation), the live code is for the WRONG
        // amount. The customer must never receive it.
        when(innbucksApi.generatePaymentCode(anyString(), anyString(), eq(5000L)))
                .thenReturn(approved("701285660", "1616800", 50L));

        InnbucksPaymentResponse resp = service.processPayment(bookingId, "+263770000001", null);

        assertEquals(Status.FAILED, resp.getStatus());
        assertEquals("amount_mismatch", resp.getUpstreamCode());
        verify(records).markFailed(any(UUID.class), eq("amount_mismatch"), contains("5000"));
        verifyNoInteractions(notifier);
        verify(records, never()).markTokenIssued(any(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void upstreamRefusal_failsRow_withUpstreamReason() {
        when(innbucksApi.generatePaymentCode(anyString(), anyString(), anyLong()))
                .thenReturn(new CodeGenerationResult(false, null, null, null, null, null,
                        "96", "Request failed, please try again later"));

        InnbucksPaymentResponse resp = service.processPayment(bookingId, "+263770000001", null);

        assertEquals(Status.FAILED, resp.getStatus());
        assertEquals("96", resp.getUpstreamCode());
        verify(records).markFailed(any(UUID.class), eq("96"), contains("Request failed"));
        verifyNoInteractions(notifier);
    }

    @Test
    void generationTransient_failsRow_andSurfaces503_neverInDoubt() {
        // Generation moves NO money: an orphaned upstream code is
        // undeliverable and self-expires, so FAILED (slot freed) is correct —
        // this flow never parks IN_DOUBT.
        when(innbucksApi.generatePaymentCode(anyString(), anyString(), anyLong()))
                .thenThrow(new InnbucksApiTransientException("innbucks 503", 503));

        InvalidPaymentRequestException ex = assertThrows(InvalidPaymentRequestException.class,
                () -> service.processPayment(bookingId, "+263770000001", null));

        assertEquals(503, ex.getStatusCode());
        verify(records).markFailed(any(UUID.class), eq("innbucks_unreachable"), anyString());
        verify(records, never()).markInDoubt(any(), anyString());
        verifyNoInteractions(notifier);
    }

    @Test
    void generationRejected_failsRow_andSurfaces500() {
        when(innbucksApi.generatePaymentCode(anyString(), anyString(), anyLong()))
                .thenThrow(new InnbucksApiException("credentials rejected", 401));

        InvalidPaymentRequestException ex = assertThrows(InvalidPaymentRequestException.class,
                () -> service.processPayment(bookingId, "+263770000001", null));

        assertEquals(500, ex.getStatusCode());
        verify(records).markFailed(any(UUID.class), eq("innbucks_rejected"), anyString());
    }

    @Test
    void deliveryFailure_isBestEffort_paymentStaysTokenIssued() {
        when(innbucksApi.generatePaymentCode(anyString(), anyString(), anyLong()))
                .thenReturn(approved("701285660", "1616800", 5000L));
        when(notifier.sendPaymentCode(anyString(), anyString(), any(), anyString(), any(), anyString()))
                .thenReturn(PaymentCodeNotifier.Delivery.FAILED);

        InnbucksPaymentResponse resp = service.processPayment(bookingId, "+263770000001", null);

        // The code is still live + echoed on the response; the journal
        // carries the miss for support. Never fail the payment over delivery.
        assertEquals(Status.PROCESSING, resp.getStatus());
        assertEquals("701285660", resp.getPaymentCode());
        verify(records).markTokenIssued(any(UUID.class), eq("701285660"), eq("1616800"),
                eq("qr-base64-bytes"), any(Instant.class));
        verify(records).noteEvent(any(UUID.class), contains("FAILED"));
        verify(records, never()).markFailed(any(), anyString(), anyString());
    }

    @Test
    void activePaymentExists_rejected409_beforeAnyUpstreamCall() {
        when(records.hasActiveOrSucceededPayment(bookingId)).thenReturn(true);

        InvalidPaymentRequestException ex = assertThrows(InvalidPaymentRequestException.class,
                () -> service.processPayment(bookingId, "+263770000001", null));

        assertEquals(409, ex.getStatusCode());
        verifyNoInteractions(innbucksApi);
        verify(records, never()).openPending(any());
    }

    @Test
    void raceLoserOnUniqueIndex_mapsTo409() {
        when(records.openPending(any(Payment.class)))
                .thenThrow(new DataIntegrityViolationException("uq_payment_active_booking"));

        InvalidPaymentRequestException ex = assertThrows(InvalidPaymentRequestException.class,
                () -> service.processPayment(bookingId, "+263770000001", null));

        assertEquals(409, ex.getStatusCode());
        verify(innbucksApi, never()).generatePaymentCode(anyString(), anyString(), anyLong());
    }

    @Test
    void toCents_exactConversions() {
        assertEquals(5000L, InnbucksPaymentService.toCents(new BigDecimal("50.00")));
        assertEquals(5000L, InnbucksPaymentService.toCents(new BigDecimal("50")));
        assertEquals(1L, InnbucksPaymentService.toCents(new BigDecimal("0.01")));
        assertEquals(123456789L, InnbucksPaymentService.toCents(new BigDecimal("1234567.89")));
        assertThrows(InvalidPaymentRequestException.class,
                () -> InnbucksPaymentService.toCents(new BigDecimal("50.005")));
    }
}
