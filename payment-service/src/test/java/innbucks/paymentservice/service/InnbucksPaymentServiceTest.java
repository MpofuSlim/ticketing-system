package innbucks.paymentservice.service;

import innbucks.paymentservice.client.BookingServiceClient;
import innbucks.paymentservice.client.CodeGenerationResult;
import innbucks.paymentservice.client.InnbucksApiClient;
import innbucks.paymentservice.client.InnbucksApiException;
import innbucks.paymentservice.client.InnbucksApiTransientException;
import innbucks.paymentservice.dto.InnbucksPaymentResponse;
import innbucks.paymentservice.dto.InnbucksPaymentResponse.Status;
import innbucks.paymentservice.entity.Payment;
import innbucks.paymentservice.service.InnbucksPaymentService.InvalidPaymentRequestException;
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
 *   <li>happy path: PENDING → TOKEN_ISSUED with the code handles + QR
 *       echoed on the PROCESSING response — the FE renders both;</li>
 *   <li>amounts convert to CENTS exactly (sub-cent precision refused) and a
 *       mismatched amount echo kills the payment BEFORE the FE ever sees
 *       the code — the 100x guard;</li>
 *   <li>generation failures (refusal/transient) close the row FAILED — no
 *       money moves on generate, the slot frees for a clean retry, and
 *       IN_DOUBT never arises in this flow;</li>
 *   <li>one active payment per booking: pre-check 409 + race-loser 409 on
 *       the unique-index violation.</li>
 * </ul>
 *
 * <p>No notifier dependency — the response IS the delivery (FE shows the
 * code/QR on the checkout screen).
 */
class InnbucksPaymentServiceTest {

    private PaymentRecordService records;
    private InnbucksApiClient innbucksApi;
    private BookingServiceClient bookings;
    private InnbucksPaymentService service;
    private CodePaymentResolutionService resolution;

    private final UUID bookingId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        records = mock(PaymentRecordService.class);
        innbucksApi = mock(InnbucksApiClient.class);
        bookings = mock(BookingServiceClient.class);
        resolution = new CodePaymentResolutionService(records, bookings,
                new innbucks.paymentservice.config.PaymentMetrics(
                        new io.micrometer.core.instrument.simple.SimpleMeterRegistry()));
        service = new InnbucksPaymentService(records, innbucksApi, bookings, resolution);
        ReflectionTestUtils.setField(service, "codeTtl", Duration.ofMinutes(10));
        ReflectionTestUtils.setField(service, "cellCurrency", "USD");

        lenient().when(records.hasActiveOrSucceededPayment(any())).thenReturn(false);
        lenient().when(bookings.getBooking(bookingId)).thenReturn(Map.of(
                "totalAmount", new BigDecimal("50.00"), "currency", "USD"));
        lenient().when(records.openPending(any(Payment.class))).thenAnswer(inv -> {
            Payment p = inv.getArgument(0);
            p.setId(UUID.randomUUID());
            p.setStatus(Payment.PaymentStatus.PENDING);
            return p;
        });
    }

    private static CodeGenerationResult approved(String code, String authNumber, Long echoCents) {
        return new CodeGenerationResult(true, code, authNumber, "qr-base64-bytes", "414107", echoCents,
                "0", "Approved or completed successfully");
    }

    @Test
    void happyPath_issuesCode_marksTokenIssued_echoesCodeAndQrOnResponse() {
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
    void amountEchoMismatch_failsRow_andNeverSurfacesTheCode() {
        // We asked for 5000 cents; the platform echoed 50 — whatever the
        // cause (unit drift, truncation), the live code is for the WRONG
        // amount. The FE must never see it.
        when(innbucksApi.generatePaymentCode(anyString(), anyString(), eq(5000L)))
                .thenReturn(approved("701285660", "1616800", 50L));

        InnbucksPaymentResponse resp = service.processPayment(bookingId, "+263770000001", null);

        assertEquals(Status.FAILED, resp.getStatus());
        assertEquals("amount_mismatch", resp.getUpstreamCode());
        assertNull(resp.getPaymentCode());
        assertNull(resp.getPaymentQrCode());
        verify(records).markFailed(any(UUID.class), eq("amount_mismatch"), contains("5000"));
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
    }

    @Test
    void generationTransient_failsRow_andSurfaces503_neverInDoubt() {
        // Generation moves NO money: an orphaned upstream code reaches no
        // one (no out-of-band delivery) and self-expires, so FAILED (slot
        // freed) is correct — this flow never parks IN_DOUBT.
        when(innbucksApi.generatePaymentCode(anyString(), anyString(), anyLong()))
                .thenThrow(new InnbucksApiTransientException("innbucks 503", 503));

        InvalidPaymentRequestException ex = assertThrows(InvalidPaymentRequestException.class,
                () -> service.processPayment(bookingId, "+263770000001", null));

        assertEquals(503, ex.getStatusCode());
        verify(records).markFailed(any(UUID.class), eq("innbucks_unreachable"), anyString());
        verify(records, never()).markInDoubt(any(), anyString());
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

    // ---------------- hold extension (the paid-but-no-ticket gap) ----------------

    @Test
    void holdExtensionRefused_409_paymentRefusedBeforeAnyLedgerWriteOrMint() {
        doThrow(new BookingServiceClient.BookingConfirmationException("Seat hold expired", 409))
                .when(bookings).extendHold(eq(bookingId), any(Instant.class));

        InvalidPaymentRequestException ex = assertThrows(InvalidPaymentRequestException.class,
                () -> service.processPayment(bookingId, "+263770000001", null));

        assertEquals(409, ex.getStatusCode());
        // ZERO side effects: no ledger row, no code minted.
        verify(records, never()).openPending(any());
        verify(innbucksApi, never()).generatePaymentCode(anyString(), anyString(), anyLong());
    }

    @Test
    void holdExtensionUnreachable_503_paymentRefusedSafely() {
        doThrow(new BookingServiceClient.BookingConfirmationException("unreachable", 503))
                .when(bookings).extendHold(eq(bookingId), any(Instant.class));

        InvalidPaymentRequestException ex = assertThrows(InvalidPaymentRequestException.class,
                () -> service.processPayment(bookingId, "+263770000001", null));

        assertEquals(503, ex.getStatusCode());
        verify(records, never()).openPending(any());
    }

    @Test
    void holdExtension_requestsCodeTtlPlusSafetyMargin() {
        when(innbucksApi.generatePaymentCode(anyString(), anyString(), anyLong()))
                .thenReturn(approved("701285660", "1616800", 5000L));

        Instant before = Instant.now();
        service.processPayment(bookingId, "+263770000001", null);

        org.mockito.ArgumentCaptor<Instant> until = org.mockito.ArgumentCaptor.forClass(Instant.class);
        verify(bookings).extendHold(eq(bookingId), until.capture());
        // ttl(10m) + margin(3m) = hold must reach >= now+13m (small clock slack).
        assertFalse(until.getValue().isBefore(before.plus(Duration.ofMinutes(13)).minusSeconds(5)),
                "hold must outlive the code by the safety margin");
    }

    // ---------------- instant check on replay ("I've paid") ----------------

    private Payment openCodeRow() {
        Payment p = Payment.builder()
                .id(UUID.randomUUID())
                .paymentReference("TKT-PMT-" + UUID.randomUUID())
                .bookingId(bookingId)
                .customerMsisdn("+263770000001")
                .amount(new BigDecimal("50.00")).currency("USD")
                .status(Payment.PaymentStatus.TOKEN_ISSUED)
                .innbucksCode("701285660").codeAuthNumber("1616800")
                .build();
        return p;
    }

    @Test
    void instantCheck_paid_confirmsBookingAndPromotes() {
        Payment row = openCodeRow();
        when(innbucksApi.inquireCodeStatus("701285660"))
                .thenReturn(new innbucks.paymentservice.client.CodeStatusResult(
                        innbucks.paymentservice.client.CodeStatusResult.Status.PAID, "Paid", null));
        when(bookings.confirmBooking(bookingId)).thenReturn(Map.of("confirmationNumber", "INN-X"));

        var outcome = service.tryResolveOpenCode(row);

        assertEquals(InnbucksPaymentService.InstantCheckOutcome.PAID, outcome);
        verify(records).markSucceeded(row.getId(), "1616800", "INN-X");
    }

    @Test
    void instantCheck_expiredUpstream_freesTheSlot() {
        Payment row = openCodeRow();
        when(innbucksApi.inquireCodeStatus("701285660"))
                .thenReturn(new innbucks.paymentservice.client.CodeStatusResult(
                        innbucks.paymentservice.client.CodeStatusResult.Status.EXPIRED, "Expired", null));

        var outcome = service.tryResolveOpenCode(row);

        assertEquals(InnbucksPaymentService.InstantCheckOutcome.EXPIRED, outcome);
        verify(records).markExpired(eq(row.getId()), contains("Expired"));
    }

    @Test
    void instantCheck_inquiryFailure_isConservativePending_rowUntouched() {
        Payment row = openCodeRow();
        when(innbucksApi.inquireCodeStatus("701285660"))
                .thenThrow(new InnbucksApiTransientException("circuit open", 503));

        var outcome = service.tryResolveOpenCode(row);

        assertEquals(InnbucksPaymentService.InstantCheckOutcome.PENDING, outcome);
        verify(records, never()).markSucceeded(any(), anyString(), any());
        verify(records, never()).markExpired(any(), anyString());
    }

    @Test
    void instantCheck_stillNew_isPending_neverGuesses() {
        Payment row = openCodeRow();
        when(innbucksApi.inquireCodeStatus("701285660"))
                .thenReturn(new innbucks.paymentservice.client.CodeStatusResult(
                        innbucks.paymentservice.client.CodeStatusResult.Status.NEW, "New", null));

        assertEquals(InnbucksPaymentService.InstantCheckOutcome.PENDING,
                service.tryResolveOpenCode(row));
        verifyNoInteractions(bookings);
    }

    @Test
    void bookingWithoutCurrency_usesTheCellCurrency_notHardcodedUsd() {
        // KE cell: booking carries no currency (single-country, no column), so
        // the payment must inherit the cell currency (KES), never a hardcoded USD.
        ReflectionTestUtils.setField(service, "cellCurrency", "KES");
        when(bookings.getBooking(bookingId)).thenReturn(Map.of("totalAmount", new BigDecimal("40.00")));
        when(innbucksApi.generatePaymentCode(anyString(), anyString(), anyLong()))
                .thenReturn(approved("701285660", "1616800", 4000L));

        InnbucksPaymentResponse resp = service.processPayment(bookingId, "+254712345678", null);

        assertEquals("KES", resp.getCurrency());
        org.mockito.ArgumentCaptor<Payment> opened = org.mockito.ArgumentCaptor.forClass(Payment.class);
        verify(records).openPending(opened.capture());
        assertEquals("KES", opened.getValue().getCurrency());
    }

    @Test
    void bookingWithExplicitCurrency_winsOverCellCurrency() {
        ReflectionTestUtils.setField(service, "cellCurrency", "KES");
        when(bookings.getBooking(bookingId)).thenReturn(Map.of(
                "totalAmount", new BigDecimal("40.00"), "currency", "USD"));
        when(innbucksApi.generatePaymentCode(anyString(), anyString(), anyLong()))
                .thenReturn(approved("701285660", "1616800", 4000L));

        InnbucksPaymentResponse resp = service.processPayment(bookingId, "+254712345678", null);

        assertEquals("USD", resp.getCurrency(), "an explicit booking currency must win over the cell default");
    }
}
