package innbucks.paymentservice.controller;

import innbucks.paymentservice.client.BookingServiceClient.BookingConfirmationException;
import innbucks.paymentservice.client.LoyaltyServiceClient;
import innbucks.paymentservice.client.LoyaltyServiceClient.LoyaltyCheckoutException;
import innbucks.paymentservice.config.PaymentMetrics;
import innbucks.paymentservice.dto.ApiResult;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import innbucks.paymentservice.dto.PaymentMethod;
import innbucks.paymentservice.dto.PaymentRequest;
import innbucks.paymentservice.dto.PaymentResponse;
import innbucks.paymentservice.dto.InnbucksPaymentResponse;
import innbucks.paymentservice.dto.ShopCheckoutRequest;
import innbucks.paymentservice.dto.ShopCheckoutResponse;
import innbucks.paymentservice.entity.Payment;
import innbucks.paymentservice.order.OrderType;
import innbucks.paymentservice.repository.PaymentRepository;
import innbucks.paymentservice.service.InnbucksPaymentService;
import innbucks.paymentservice.service.InnbucksPaymentService.InvalidPaymentRequestException;
import innbucks.paymentservice.service.PaymentRecordService;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class PaymentControllerTest {

    // Fresh registry per controller so tests can assert counters without
    // bleeding state across @Test methods.
    private static PaymentMetrics newMetrics() {
        return new PaymentMetrics(new SimpleMeterRegistry());
    }

    // shopCheckout derives the customer's MSISDN from the authenticated
    // principal; tests stub a token whose `getName()` returns the customer's
    // phone. JwtFilter does the same in prod (extractPhoneNumber claim).
    private static final Authentication AUTH_0712345678 =
            new UsernamePasswordAuthenticationToken("0712345678", null, java.util.List.of());

    private PaymentRequest paymentFor(UUID bookingId) {
        PaymentRequest r = new PaymentRequest();
        r.setBookingId(bookingId);
        return r;
    }

    private PaymentRequest marketplacePayment(String orderRef) {
        PaymentRequest r = new PaymentRequest();
        r.setOrderType(OrderType.MARKETPLACE);
        r.setOrderRef(orderRef);
        return r;
    }

    /** Controller with mockable real-flow collaborators. */
    private record Fixture(PaymentController controller,
                           InnbucksPaymentService innbucks, PaymentRepository payments,
                           PaymentRecordService records,
                           innbucks.paymentservice.service.ZimswitchCardPaymentService card) {}

    private static Fixture fixture() {
        InnbucksPaymentService innbucks = mock(InnbucksPaymentService.class);
        PaymentRepository payments = mock(PaymentRepository.class);
        PaymentRecordService records = mock(PaymentRecordService.class);
        var card = mock(innbucks.paymentservice.service.ZimswitchCardPaymentService.class);
        PaymentController controller = new PaymentController(
                mock(LoyaltyServiceClient.class), newMetrics(),
                innbucks, card,
                new innbucks.paymentservice.client.ZimswitchProperties(),
                mock(innbucks.paymentservice.service.EcocashPaymentService.class), records, payments);
        org.springframework.test.util.ReflectionTestUtils.setField(controller, "cellCurrency", "USD");
        // Default: the instant check reports PENDING (poller stays authoritative)
        // so replay tests exercise the plain replay path unless they say otherwise.
        lenient().when(innbucks.tryResolveOpenCode(any()))
                .thenReturn(InnbucksPaymentService.InstantCheckOutcome.PENDING);
        return new Fixture(controller, innbucks, payments, records, card);
    }

    /** Stubs the repository's replay lookup for a BOOKING order key. */
    private static org.mockito.stubbing.OngoingStubbing<Optional<Payment>> replayLookup(
            Fixture f, UUID bookingId) {
        return when(f.payments().findFirstByOrderTypeAndOrderRefAndStatusInOrderByCreatedAtDesc(
                eq(OrderType.BOOKING), eq(bookingId.toString()), any()));
    }

    private static InnbucksPaymentResponse outcome(UUID bookingId, InnbucksPaymentResponse.Status status,
                                                   String paymentReference, String confirmation) {
        return outcome(bookingId, status, paymentReference, confirmation, UUID.randomUUID());
    }

    private static InnbucksPaymentResponse outcome(UUID bookingId, InnbucksPaymentResponse.Status status,
                                                   String paymentReference, String confirmation,
                                                   UUID paymentId) {
        return InnbucksPaymentResponse.builder()
                .paymentId(paymentId)
                .paymentReference(paymentReference)
                .bookingId(bookingId)
                .orderType(OrderType.BOOKING)
                .orderRef(bookingId.toString())
                .status(status)
                .amountPaid(new BigDecimal("100.00"))
                .currency("USD")
                .confirmationNumber(confirmation)
                .build();
    }

    @Test
    void processPayment_success_keepsStubReceiptShape() {
        Fixture f = fixture();
        UUID bookingId = UUID.randomUUID();
        UUID paymentRowId = UUID.randomUUID();
        replayLookup(f, bookingId).thenReturn(Optional.empty());
        when(f.innbucks().processPayment(eq(OrderType.BOOKING), eq(bookingId.toString()), isNull(), isNull()))
                .thenReturn(outcome(bookingId, InnbucksPaymentResponse.Status.SUCCESS,
                        // The modern settlement format — TKZ-<TAG>-<hex>, no UUID
                        // in it. transactionId must come from the ledger row id,
                        // not from parsing this.
                        "TKZ-PINKRUN26-4F3A2B1C0D9E", "INN-20260502-AB12CD", paymentRowId));

        ResponseEntity<ApiResult<PaymentResponse>> resp =
                f.controller().processPayment(paymentFor(bookingId));

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        PaymentResponse data = resp.getBody().getData();
        assertEquals(PaymentResponse.Status.SUCCESS, data.getStatus());
        assertEquals(bookingId, data.getBookingId());
        assertEquals(0, new BigDecimal("100.00").compareTo(data.getAmountPaid()));
        assertEquals("USD", data.getCurrency());
        assertEquals("INN-20260502-AB12CD", data.getConfirmationNumber());
        // transactionId is the payment ROW id — stable across the first
        // response and every replay. It used to be parsed out of the payment
        // reference, which silently produced a fresh random UUID per call once
        // references moved to the TKZ-<TAG>-<hex> format.
        assertEquals(paymentRowId, data.getTransactionId());
        assertNotNull(data.getProcessedAt());
        // Additive order identity — booking rows still echo bookingId.
        assertEquals(OrderType.BOOKING, data.getOrderType());
        assertEquals(bookingId.toString(), data.getOrderRef());
    }

    @Test
    void processPayment_codeIssued_returns200Processing_withTheCodeEchoed() {
        Fixture f = fixture();
        UUID bookingId = UUID.randomUUID();
        java.time.LocalDateTime expiresAt = java.time.LocalDateTime.now(java.time.ZoneOffset.UTC).plusMinutes(10);
        replayLookup(f, bookingId).thenReturn(Optional.empty());
        when(f.innbucks().processPayment(eq(OrderType.BOOKING), eq(bookingId.toString()), isNull(), isNull()))
                .thenReturn(InnbucksPaymentResponse.builder()
                        .paymentReference("TKT-PMT-" + UUID.randomUUID())
                        .bookingId(bookingId)
                        .orderType(OrderType.BOOKING)
                        .orderRef(bookingId.toString())
                        .status(InnbucksPaymentResponse.Status.PROCESSING)
                        .amountPaid(new BigDecimal("100.00"))
                        .currency("USD")
                        .upstreamMessage("Approve the payment in your InnBucks app — your payment code was sent to your phone")
                        .paymentCode("701285660")
                        .paymentCodeExpiresAt(expiresAt)
                        .paymentQrCode("qr-base64-bytes")
                        .build());

        ResponseEntity<ApiResult<PaymentResponse>> resp =
                f.controller().processPayment(paymentFor(bookingId));

        // 200 (not 202): the FE's stub contract treats non-200 as error.
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        PaymentResponse data = resp.getBody().getData();
        assertEquals(PaymentResponse.Status.PROCESSING, data.getStatus());
        // Additive fields: the code rides along; the FE renders code + QR.
        assertEquals("701285660", data.getPaymentCode());
        assertEquals(expiresAt, data.getPaymentCodeExpiresAt());
        assertEquals("qr-base64-bytes", data.getPaymentQrCode());
        assertTrue(resp.getBody().getMessage().contains("InnBucks app"));
        // The rail MUST be stated even on the default path. Clients branch on
        // the rail they RECEIVE (an open attempt on the other rail comes back
        // instead of the one requested), so a null here makes the DEFAULT,
        // most-travelled response the one an FE with explicit per-rail arms
        // cannot dispatch — and it only shows on the first response, because
        // the replay path did populate it.
        assertEquals(innbucks.paymentservice.entity.PaymentRail.INNBUCKS_CODE, data.getPaymentRail());
    }

    @Test
    void processPayment_marketplaceOrder_flowsThroughTheGatewayKey() {
        Fixture f = fixture();
        String orderRef = "MKT-4F9A1C22B7D3";
        when(f.payments().findFirstByOrderTypeAndOrderRefAndStatusInOrderByCreatedAtDesc(
                eq(OrderType.MARKETPLACE), eq(orderRef), any()))
                .thenReturn(Optional.empty());
        when(f.innbucks().processPayment(eq(OrderType.MARKETPLACE), eq(orderRef), isNull(), isNull()))
                .thenReturn(InnbucksPaymentResponse.builder()
                        .paymentReference("TKZ-MKT-4F3A2B1C0D9E")
                        .orderType(OrderType.MARKETPLACE)
                        .orderRef(orderRef)
                        .status(InnbucksPaymentResponse.Status.PROCESSING)
                        .amountPaid(new BigDecimal("35.50"))
                        .currency("USD")
                        .paymentCode("701442918")
                        .build());

        ResponseEntity<ApiResult<PaymentResponse>> resp =
                f.controller().processPayment(marketplacePayment(orderRef));

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        PaymentResponse data = resp.getBody().getData();
        assertEquals(PaymentResponse.Status.PROCESSING, data.getStatus());
        assertEquals(OrderType.MARKETPLACE, data.getOrderType());
        assertEquals(orderRef, data.getOrderRef());
        assertNull(data.getBookingId(), "marketplace payments carry no bookingId");
        assertEquals("701442918", data.getPaymentCode());
    }

    /** Replay a BOOKING row in the given ledger state and return the response. */
    private PaymentResponse replayInState(Fixture f, UUID bookingId,
                                          Payment.PaymentStatus state,
                                          java.util.function.Consumer<Payment.PaymentBuilder> tweak) {
        Payment.PaymentBuilder b = Payment.builder()
                .id(UUID.randomUUID())
                .paymentReference("TKT-PMT-" + UUID.randomUUID())
                .orderType(OrderType.BOOKING).orderRef(bookingId.toString())
                .bookingId(bookingId).customerMsisdn("+263770000001")
                .amount(new BigDecimal("100.00")).currency("USD")
                .paymentRail(innbucks.paymentservice.entity.PaymentRail.INNBUCKS_CODE)
                .createdAt(java.time.Instant.now())
                .status(state);
        tweak.accept(b);
        replayLookup(f, bookingId).thenReturn(Optional.of(b.build()));
        return f.controller().processPayment(paymentFor(bookingId)).getBody().getData();
    }

    @Test
    void replay_stageDiscriminatesTheSixProcessingStates_andAnswersTheMoneyQuestion() {
        // `status` collapses all of these to PROCESSING; `stage` is what the
        // FE branches on, and fundsCaptured is the money question. Reading
        // the human-facing `message` was previously the ONLY discriminator.

        // Money HAS moved — the confident "payment received, confirming" screen.
        PaymentResponse unconfirmed = replayInState(fixture(), UUID.randomUUID(),
                Payment.PaymentStatus.COMPLETED_UNCONFIRMED, b -> { });
        assertEquals(PaymentResponse.Status.PROCESSING, unconfirmed.getStatus());
        assertEquals(PaymentResponse.Stage.PAYMENT_RECEIVED, unconfirmed.getStage());
        assertEquals(Boolean.TRUE, unconfirmed.getFundsCaptured());

        // Nothing charged — a live code awaiting the customer.
        PaymentResponse awaiting = replayInState(fixture(), UUID.randomUUID(),
                Payment.PaymentStatus.TOKEN_ISSUED,
                b -> b.innbucksCode("701285660")
                        .codeExpiresAt(java.time.Instant.now().plusSeconds(300)));
        assertEquals(PaymentResponse.Status.PROCESSING, awaiting.getStatus());
        assertEquals(PaymentResponse.Stage.AWAITING_PAYMENT, awaiting.getStage());
        assertEquals(Boolean.FALSE, awaiting.getFundsCaptured());

        // Nothing charged — the code lapsed; retry mints a fresh one.
        PaymentResponse expired = replayInState(fixture(), UUID.randomUUID(),
                Payment.PaymentStatus.TOKEN_ISSUED,
                b -> b.innbucksCode("701285660")
                        .codeExpiresAt(java.time.Instant.now().minusSeconds(60)));
        assertEquals(PaymentResponse.Stage.INSTRUMENT_EXPIRED, expired.getStage());
        // null, NOT false. A row is only SEEN in this state while the
        // reconciler has not yet concluded the instrument went unpaid — and
        // it deliberately refuses to conclude that when the upstream status
        // is unreadable, because the customer may have paid. A row proven
        // unpaid becomes terminal EXPIRED and stops being replayed at all.
        assertNull(expired.getFundsCaptured());

        // Nothing charged — request in flight, no instrument minted yet.
        PaymentResponse pending = replayInState(fixture(), UUID.randomUUID(),
                Payment.PaymentStatus.PENDING, b -> { });
        assertEquals(PaymentResponse.Stage.IN_PROGRESS, pending.getStage());
        assertEquals(Boolean.FALSE, pending.getFundsCaptured());

        // UNKNOWN — never claim either way to the customer.
        PaymentResponse inDoubt = replayInState(fixture(), UUID.randomUUID(),
                Payment.PaymentStatus.IN_DOUBT, b -> { });
        assertEquals(PaymentResponse.Stage.VERIFYING, inDoubt.getStage());
        assertNull(inDoubt.getFundsCaptured(),
                "IN_DOUBT must report null, not false — money may have moved");

        // Terminal success.
        PaymentResponse done = replayInState(fixture(), UUID.randomUUID(),
                Payment.PaymentStatus.SUCCEEDED, b -> b.confirmationNumber("INN-1"));
        assertEquals(PaymentResponse.Status.SUCCESS, done.getStatus());
        assertEquals(PaymentResponse.Stage.COMPLETED, done.getStage());
        assertEquals(Boolean.TRUE, done.getFundsCaptured());
    }

    @Test
    void cardInstantCheck_echoMismatchParkedInDoubt_isNeverReportedAsNotCharged() {
        // The instant check returns PENDING for an echo mismatch (the money
        // outcome is UNKNOWN, so it is not a PAID result) but has ALREADY
        // parked the row IN_DOUBT in its own REQUIRES_NEW transaction. The
        // caller's in-memory row still says TOKEN_ISSUED, so replaying it
        // would answer "awaiting payment / you were not charged" — and
        // re-render the card widget — for a payment that may have taken the
        // customer's money. The controller must re-read.
        Fixture f = fixture();
        UUID bookingId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();

        Payment.PaymentBuilder base = Payment.builder()
                .id(paymentId).paymentReference("TKZ-PINKRUN26-4F3A2B1C0D9E")
                .orderType(OrderType.BOOKING).orderRef(bookingId.toString())
                .bookingId(bookingId).customerMsisdn("+263770000001")
                .amount(new BigDecimal("92.00")).currency("USD")
                .paymentRail(innbucks.paymentservice.entity.PaymentRail.ZIMSWITCH_CARD)
                .checkoutId("67b6338009845562954024F5A44FC2AE.uat01-vm-tx04")
                .createdAt(java.time.Instant.now())
                .codeExpiresAt(java.time.Instant.now().plusSeconds(600));

        // What the replay lookup hands the controller: the STALE pre-check row.
        replayLookup(f, bookingId).thenReturn(Optional.of(
                base.status(Payment.PaymentStatus.TOKEN_ISSUED).build()));
        // The instant check parks IN_DOUBT and reports PENDING.
        when(f.card().resolveOpenCheckout(any(), any()))
                .thenReturn(innbucks.paymentservice.service.ZimswitchCardPaymentService
                        .CardCheckOutcome.PENDING);
        // The DB now holds the parked row.
        when(f.payments().findById(paymentId)).thenReturn(Optional.of(
                base.status(Payment.PaymentStatus.IN_DOUBT).build()));

        PaymentResponse data = f.controller().processPayment(paymentFor(bookingId)).getBody().getData();

        assertEquals(PaymentResponse.Stage.VERIFYING, data.getStage(),
                "a row parked IN_DOUBT must report VERIFYING, not AWAITING_PAYMENT");
        assertNull(data.getFundsCaptured(),
                "money may have moved — never answer false here");
        // And critically: do not invite a second payment on that checkout.
        assertNull(data.getCheckoutId());
        assertNull(data.getCheckoutScriptUrl());
        assertNull(data.getShopperResultUrl());
    }

    @Test
    void freshCodeIssue_isAwaitingPayment_notCaptured() {
        Fixture f = fixture();
        UUID bookingId = UUID.randomUUID();
        replayLookup(f, bookingId).thenReturn(Optional.empty());
        when(f.innbucks().processPayment(eq(OrderType.BOOKING), eq(bookingId.toString()), isNull(), isNull()))
                .thenReturn(InnbucksPaymentResponse.builder()
                        .paymentReference("TKT-PMT-" + UUID.randomUUID())
                        .bookingId(bookingId).orderType(OrderType.BOOKING)
                        .orderRef(bookingId.toString())
                        .status(InnbucksPaymentResponse.Status.PROCESSING)
                        .amountPaid(new BigDecimal("100.00")).currency("USD")
                        .paymentCode("701285660")
                        .build());

        PaymentResponse data = f.controller().processPayment(paymentFor(bookingId)).getBody().getData();

        assertEquals(PaymentResponse.Stage.AWAITING_PAYMENT, data.getStage());
        assertEquals(Boolean.FALSE, data.getFundsCaptured());
    }

    @Test
    void processPayment_marketplaceReplay_returnsExistingReceipt() {
        Fixture f = fixture();
        String orderRef = "MKT-4F9A1C22B7D3";
        Payment paid = Payment.builder()
                .id(UUID.randomUUID()).paymentReference("TKZ-MKT-" + "AABBCCDDEEFF")
                .orderType(OrderType.MARKETPLACE).orderRef(orderRef)
                .customerMsisdn("+263770000001")
                .amount(new BigDecimal("35.50")).currency("USD")
                .status(Payment.PaymentStatus.SUCCEEDED)
                .confirmationNumber(orderRef)
                .build();
        when(f.payments().findFirstByOrderTypeAndOrderRefAndStatusInOrderByCreatedAtDesc(
                eq(OrderType.MARKETPLACE), eq(orderRef), any()))
                .thenReturn(Optional.of(paid));

        ResponseEntity<ApiResult<PaymentResponse>> resp =
                f.controller().processPayment(marketplacePayment(orderRef));

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals(PaymentResponse.Status.SUCCESS, resp.getBody().getData().getStatus());
        assertEquals(OrderType.MARKETPLACE, resp.getBody().getData().getOrderType());
        assertEquals(orderRef, resp.getBody().getData().getOrderRef());
        verifyNoInteractions(f.innbucks());
    }

    // ---- request validation: exactly one of bookingId / (orderType+orderRef) ----

    @Test
    void processPayment_noOrderIdentityAtAll_is400() {
        Fixture f = fixture();

        ResponseEntity<ApiResult<PaymentResponse>> resp =
                f.controller().processPayment(new PaymentRequest());

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        assertTrue(resp.getBody().getMessage().contains("exactly one of bookingId or orderType + orderRef"));
        verifyNoInteractions(f.innbucks(), f.payments());
    }

    @Test
    void processPayment_bothBookingIdAndOrderPair_is400() {
        Fixture f = fixture();
        PaymentRequest r = new PaymentRequest();
        r.setBookingId(UUID.randomUUID());
        r.setOrderType(OrderType.MARKETPLACE);
        r.setOrderRef("MKT-1");

        ResponseEntity<ApiResult<PaymentResponse>> resp = f.controller().processPayment(r);

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        assertTrue(resp.getBody().getMessage().contains("not both"));
        verifyNoInteractions(f.innbucks(), f.payments());
    }

    @Test
    void processPayment_orderTypeWithoutOrderRef_is400() {
        Fixture f = fixture();
        PaymentRequest r = new PaymentRequest();
        r.setOrderType(OrderType.MARKETPLACE);

        ResponseEntity<ApiResult<PaymentResponse>> resp = f.controller().processPayment(r);

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        verifyNoInteractions(f.innbucks(), f.payments());
    }

    @Test
    void processPayment_bookingOrderTypeWithNonUuidRef_is400() {
        Fixture f = fixture();
        PaymentRequest r = new PaymentRequest();
        r.setOrderType(OrderType.BOOKING);
        r.setOrderRef("not-a-uuid");

        ResponseEntity<ApiResult<PaymentResponse>> resp = f.controller().processPayment(r);

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        assertTrue(resp.getBody().getMessage().contains("booking UUID"));
        verifyNoInteractions(f.innbucks(), f.payments());
    }

    @Test
    void processPayment_decline_surfacesAs400WithReason() {
        Fixture f = fixture();
        UUID bookingId = UUID.randomUUID();
        replayLookup(f, bookingId).thenReturn(Optional.empty());
        when(f.innbucks().processPayment(eq(OrderType.BOOKING), eq(bookingId.toString()), isNull(), isNull()))
                .thenThrow(new InvalidPaymentRequestException(
                        "Insufficient balance in your InnBucks wallet", 422));

        ResponseEntity<ApiResult<PaymentResponse>> resp =
                f.controller().processPayment(paymentFor(bookingId));

        // Stub error vocabulary: non-200 + human message, data null.
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        assertEquals("Insufficient balance in your InnBucks wallet", resp.getBody().getMessage());
        assertNull(resp.getBody().getData());
    }

    @Test
    void processPayment_replayOnPaidBooking_returnsExistingReceipt_neverErrors() {
        Fixture f = fixture();
        UUID bookingId = UUID.randomUUID();
        Payment paid = Payment.builder()
                .id(UUID.randomUUID()).paymentReference("TKT-PMT-" + UUID.randomUUID())
                .bookingId(bookingId).customerMsisdn("+263770000001")
                .amount(new BigDecimal("100.00")).currency("USD")
                .status(Payment.PaymentStatus.SUCCEEDED)
                .confirmationNumber("INN-REPLAY-1")
                .build();
        replayLookup(f, bookingId).thenReturn(Optional.of(paid));

        ResponseEntity<ApiResult<PaymentResponse>> resp =
                f.controller().processPayment(paymentFor(bookingId));

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals(PaymentResponse.Status.SUCCESS, resp.getBody().getData().getStatus());
        assertEquals("INN-REPLAY-1", resp.getBody().getData().getConfirmationNumber());
        // A resolved payment exposes no code — there is nothing left to approve.
        assertNull(resp.getBody().getData().getPaymentCode());
        // The real-money flow must NOT run again — double-tap is a replay.
        verifyNoInteractions(f.innbucks());
    }

    @Test
    void processPayment_orphanedPending_isClosedAndReplacedByAFreshAttempt() {
        // The bug from live testing: a crash/outage leftover PENDING row (no
        // code ever recorded) replayed as PROCESSING with null code/QR —
        // nothing for the customer to pay, forever. It must be closed FAILED
        // and a fresh attempt run in the same request.
        Fixture f = fixture();
        UUID bookingId = UUID.randomUUID();
        Payment orphan = Payment.builder()
                .id(UUID.randomUUID()).paymentReference("TKT-PMT-" + UUID.randomUUID())
                .bookingId(bookingId).customerMsisdn("+263770000001")
                .amount(new BigDecimal("10.00")).currency("USD")
                .status(Payment.PaymentStatus.PENDING)
                .createdAt(java.time.Instant.now().minusSeconds(120))
                .build();
        replayLookup(f, bookingId).thenReturn(Optional.of(orphan));
        when(f.innbucks().processPayment(eq(OrderType.BOOKING), eq(bookingId.toString()), isNull(), isNull()))
                .thenReturn(InnbucksPaymentResponse.builder()
                        .paymentReference("TKT-PMT-" + UUID.randomUUID())
                        .bookingId(bookingId)
                        .status(InnbucksPaymentResponse.Status.PROCESSING)
                        .amountPaid(new BigDecimal("10.00")).currency("USD")
                        .paymentCode("701999000")
                        .build());

        ResponseEntity<ApiResult<PaymentResponse>> resp =
                f.controller().processPayment(paymentFor(bookingId));

        verify(f.records()).markFailed(eq(orphan.getId()), eq("stale_pending"), anyString());
        verify(f.innbucks()).processPayment(eq(OrderType.BOOKING), eq(bookingId.toString()), isNull(), isNull());
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals("701999000", resp.getBody().getData().getPaymentCode(),
                "the customer must get the FRESH code, not the orphan's nothing");
    }

    @Test
    void processPayment_youngPending_replaysHonestly_neverDoubleGenerates() {
        // A PENDING row seconds old is a genuinely concurrent request —
        // racing a second generation could mint two live codes.
        Fixture f = fixture();
        UUID bookingId = UUID.randomUUID();
        Payment inFlight = Payment.builder()
                .id(UUID.randomUUID()).paymentReference("TKT-PMT-" + UUID.randomUUID())
                .bookingId(bookingId).customerMsisdn("+263770000001")
                .amount(new BigDecimal("10.00")).currency("USD")
                .status(Payment.PaymentStatus.PENDING)
                .createdAt(java.time.Instant.now().minusSeconds(3))
                .build();
        replayLookup(f, bookingId).thenReturn(Optional.of(inFlight));

        ResponseEntity<ApiResult<PaymentResponse>> resp =
                f.controller().processPayment(paymentFor(bookingId));

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals(PaymentResponse.Status.PROCESSING, resp.getBody().getData().getStatus());
        assertNull(resp.getBody().getData().getPaymentCode());
        assertTrue(resp.getBody().getMessage().contains("already being processed"));
        verifyNoInteractions(f.innbucks());
        verify(f.records(), never()).markFailed(any(), anyString(), anyString());
    }

    @Test
    void processPayment_replayWithExpiredCode_neverResurfacesTheDeadCode() {
        Fixture f = fixture();
        UUID bookingId = UUID.randomUUID();
        Payment expired = Payment.builder()
                .id(UUID.randomUUID()).paymentReference("TKT-PMT-" + UUID.randomUUID())
                .bookingId(bookingId).customerMsisdn("+263770000001")
                .amount(new BigDecimal("10.00")).currency("USD")
                .status(Payment.PaymentStatus.TOKEN_ISSUED)
                .innbucksCode("701285660").codeAuthNumber("1616800")
                .codeExpiresAt(java.time.Instant.now().minusSeconds(60))
                .createdAt(java.time.Instant.now().minusSeconds(700))
                .build();
        replayLookup(f, bookingId).thenReturn(Optional.of(expired));

        ResponseEntity<ApiResult<PaymentResponse>> resp =
                f.controller().processPayment(paymentFor(bookingId));

        PaymentResponse data = resp.getBody().getData();
        assertEquals(PaymentResponse.Status.PROCESSING, data.getStatus());
        assertNull(data.getPaymentCode(), "a dead code must never be advertised again");
        assertNull(data.getPaymentQrCode());
        assertTrue(resp.getBody().getMessage().contains("expired"));
        // The instant check MAY consult InnBucks; what must never happen is a
        // fresh generation from a replay of a locally-expired code.
        verify(f.innbucks(), never()).processPayment(any(), anyString(), any(), any());
    }

    @Test
    void processPayment_replayOnPaidUnconfirmed_hidesTheSpentCode() {
        Fixture f = fixture();
        UUID bookingId = UUID.randomUUID();
        Payment cu = Payment.builder()
                .id(UUID.randomUUID()).paymentReference("TKT-PMT-" + UUID.randomUUID())
                .bookingId(bookingId).customerMsisdn("+263770000001")
                .amount(new BigDecimal("10.00")).currency("USD")
                .status(Payment.PaymentStatus.COMPLETED_UNCONFIRMED)
                .innbucksCode("701285660").codeAuthNumber("1616800")
                .codeExpiresAt(java.time.Instant.now().plusSeconds(300))
                .createdAt(java.time.Instant.now().minusSeconds(60))
                .build();
        replayLookup(f, bookingId).thenReturn(Optional.of(cu));

        ResponseEntity<ApiResult<PaymentResponse>> resp =
                f.controller().processPayment(paymentFor(bookingId));

        PaymentResponse data = resp.getBody().getData();
        assertEquals(PaymentResponse.Status.PROCESSING, data.getStatus());
        assertNull(data.getPaymentCode(), "a PAID code is spent — re-showing it invites confusion");
        assertTrue(resp.getBody().getMessage().contains("being confirmed"));
    }

    @Test
    void processPayment_replayWhileCodeOpen_reSurfacesTheLiveCode() {
        // The lost-SMS recovery path: re-POSTing while the code is still
        // awaiting approval returns the SAME code (never a second one).
        Fixture f = fixture();
        UUID bookingId = UUID.randomUUID();
        java.time.Instant expiresAt = java.time.Instant.now().plusSeconds(540);
        Payment open = Payment.builder()
                .id(UUID.randomUUID()).paymentReference("TKT-PMT-" + UUID.randomUUID())
                .bookingId(bookingId).customerMsisdn("+263770000001")
                .amount(new BigDecimal("100.00")).currency("USD")
                .status(Payment.PaymentStatus.TOKEN_ISSUED)
                .innbucksCode("701285660")
                .codeAuthNumber("1616800")
                .codeQrBase64("qr-base64-bytes")
                .codeExpiresAt(expiresAt)
                .build();
        replayLookup(f, bookingId).thenReturn(Optional.of(open));

        ResponseEntity<ApiResult<PaymentResponse>> resp =
                f.controller().processPayment(paymentFor(bookingId));

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        PaymentResponse data = resp.getBody().getData();
        assertEquals(PaymentResponse.Status.PROCESSING, data.getStatus());
        assertEquals("701285660", data.getPaymentCode());
        assertEquals("qr-base64-bytes", data.getPaymentQrCode(),
                "replay must re-surface the QR too");
        assertEquals(java.time.LocalDateTime.ofInstant(expiresAt, java.time.ZoneOffset.UTC),
                data.getPaymentCodeExpiresAt());
        verify(f.innbucks(), never()).processPayment(any(), anyString(), any(), any());
    }

    @Test
    void processPayment_bookingWithoutPhone_surfacesTheServices400() {
        Fixture f = fixture();
        UUID bookingId = UUID.randomUUID();
        replayLookup(f, bookingId).thenReturn(Optional.empty());
        // The payer resolution moved into the service (gateway snapshot); the
        // controller must relay its 400 verbatim, data null.
        when(f.innbucks().processPayment(eq(OrderType.BOOKING), eq(bookingId.toString()), isNull(), isNull()))
                .thenThrow(new InvalidPaymentRequestException(
                        "Booking has no payer phone number — create the booking with a phone number to pay via InnBucks",
                        400));

        ResponseEntity<ApiResult<PaymentResponse>> resp =
                f.controller().processPayment(paymentFor(bookingId));

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        assertNull(resp.getBody().getData());
    }

    @Test
    void processPayment_surfacesBookingServiceErrorMessage() {
        Fixture f = fixture();
        UUID bookingId = UUID.randomUUID();
        replayLookup(f, bookingId).thenReturn(Optional.empty());
        when(f.innbucks().processPayment(eq(OrderType.BOOKING), eq(bookingId.toString()), isNull(), isNull()))
                .thenThrow(new BookingConfirmationException("Booking not found", 404));

        ResponseEntity<ApiResult<PaymentResponse>> resp =
                f.controller().processPayment(paymentFor(bookingId));

        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode());
        assertEquals("Booking not found", resp.getBody().getMessage());
        assertNull(resp.getBody().getData());
    }

    /**
     * Every failure example on POST /payments is a body the code really sends,
     * and every hold or product-read failure the code sends is documented.
     * Each failure below comes from the REAL gateway or client failing, through
     * the REAL controller mapping — no message is restated here. A refused hold
     * used to be documented as {@code 400 "Seat hold expired"}, which nothing
     * sends: the gateways answer 409 (rebook, nothing charged) or 503 (retry),
     * so a client built from Swagger missed its rebook branch.
     */
    @Test
    void processPayment_swaggerFailureExamples_areTheBodiesTheCodeSends() throws Exception {
        Map<Integer, Set<String>> documented = new HashMap<>();
        com.fasterxml.jackson.databind.ObjectMapper json = new com.fasterxml.jackson.databind.ObjectMapper();
        ApiResponses responses = PaymentController.class
                .getMethod("processPayment", PaymentRequest.class).getAnnotation(ApiResponses.class);
        for (ApiResponse response : responses.value()) {
            int status = Integer.parseInt(response.responseCode());
            if (status < 400) continue;
            for (ExampleObject example : response.content()[0].examples()) {
                var body = json.readTree(example.value());
                assertTrue(body.get("code").asText().startsWith(status + " "), example.name());
                documented.computeIfAbsent(status, s -> new HashSet<>())
                        .add(body.get("code").asText() + " | " + body.get("message").asText());
            }
        }

        Map<Integer, Set<String>> sent = new HashMap<>();
        // 400: the two order-identification refusals.
        recordSent(sent, fixture().controller().processPayment(new PaymentRequest()));
        PaymentRequest both = marketplacePayment("MKT-4F9A1C22B7D3");
        both.setBookingId(UUID.randomUUID());
        recordSent(sent, fixture().controller().processPayment(both));

        // Booking: booking-service refusing the hold (any of its refusal
        // statuses), failing it, and unreachable on the fetch.
        UUID bookingId = UUID.randomUUID();
        for (int refusal : new int[] {404, 409, 400, 500}) {
            var bookings = mock(innbucks.paymentservice.client.BookingServiceClient.class);
            doThrow(new BookingConfirmationException("booking-service says no", refusal))
                    .when(bookings).extendHold(eq(bookingId), any());
            var gateway = new innbucks.paymentservice.order.BookingOrderGateway(
                    bookings, mock(innbucks.paymentservice.client.EventServiceClient.class), "USD");
            recordSent(sent, payFailing(paymentFor(bookingId),
                    () -> gateway.extendHold(bookingId.toString(), java.time.Instant.now())));
        }
        try (var pool = new innbucks.paymentservice.config.PooledHttpClient(
                new innbucks.paymentservice.config.PooledHttpClientProperties())) {
            var unreachable = new innbucks.paymentservice.client.BookingServiceClient(
                    org.springframework.web.client.RestClient.builder(), "http://127.0.0.1:" + closedPort(),
                    500, 500, "token", json, pool);
            var gateway = new innbucks.paymentservice.order.BookingOrderGateway(
                    unreachable, mock(innbucks.paymentservice.client.EventServiceClient.class), "USD");
            recordSent(sent, payFailing(paymentFor(bookingId), () -> gateway.fetch(bookingId.toString())));
        }

        // Marketplace and voucher orders: the same three failures.
        for (int status : new int[] {409, 500}) {
            var market = mock(innbucks.paymentservice.client.MarketplaceOrderClient.class);
            var marketError = new innbucks.paymentservice.client.MarketplaceOrderClient
                    .MarketplaceOrderException("marketplace says no", status, "order_not_pending");
            doThrow(marketError).when(market).extendExpiry(anyString(), anyInt());
            when(market.getOrder(anyString())).thenThrow(marketError);
            var marketGateway = new innbucks.paymentservice.order.MarketplaceOrderGateway(market);
            recordSent(sent, payFailing(marketplacePayment("MKT-4F9A1C22B7D3"),
                    () -> marketGateway.extendHold("MKT-4F9A1C22B7D3", java.time.Instant.now())));
            recordSent(sent, payFailing(marketplacePayment("MKT-4F9A1C22B7D3"),
                    () -> marketGateway.fetch("MKT-4F9A1C22B7D3")));

            var vouchers = mock(innbucks.paymentservice.client.LoyaltyVoucherOrderClient.class);
            var voucherError = new innbucks.paymentservice.client.LoyaltyVoucherOrderClient
                    .VoucherOrderException("loyalty says no", status, "ORDER_NOT_PENDING");
            doThrow(voucherError).when(vouchers).extendExpiry(anyString(), anyInt());
            when(vouchers.getOrder(anyString())).thenThrow(voucherError);
            var voucherGateway = new innbucks.paymentservice.order.LoyaltyVoucherOrderGateway(vouchers);
            PaymentRequest voucherOrder = new PaymentRequest();
            voucherOrder.setOrderType(OrderType.LOYALTY_VOUCHER);
            voucherOrder.setOrderRef("VCH-4F9A1C22B7D3");
            recordSent(sent, payFailing(voucherOrder,
                    () -> voucherGateway.extendHold("VCH-4F9A1C22B7D3", java.time.Instant.now())));
            recordSent(sent, payFailing(voucherOrder, () -> voucherGateway.fetch("VCH-4F9A1C22B7D3")));
        }

        assertEquals(Set.of(400, 409, 503), sent.keySet(), "every failure above is a 400, 409 or 503");
        assertTrue(sent.get(400).containsAll(documented.get(400)),
                "a 400 example nothing sends: " + diff(documented.get(400), sent.get(400)));
        for (int status : new int[] {409, 503}) {
            assertTrue(documented.get(status).containsAll(sent.get(status)),
                    "sent but undocumented " + status + ": " + diff(sent.get(status), documented.get(status)));
        }
    }

    /** Drives {@code failure} where the rail service would have run, through the real controller. */
    private static ResponseEntity<ApiResult<PaymentResponse>> payFailing(PaymentRequest request, Runnable failure) {
        Fixture f = fixture();
        when(f.innbucks().processPayment(any(), anyString(), isNull(), isNull())).thenAnswer(inv -> {
            failure.run();
            throw new AssertionError("the failure did not throw");
        });
        return f.controller().processPayment(request);
    }

    private static void recordSent(Map<Integer, Set<String>> sent, ResponseEntity<ApiResult<PaymentResponse>> resp) {
        assertNull(resp.getBody().getData());
        sent.computeIfAbsent(resp.getStatusCode().value(), s -> new HashSet<>())
                .add(resp.getBody().getCode() + " | " + resp.getBody().getMessage());
    }

    private static Set<String> diff(Set<String> a, Set<String> b) {
        Set<String> d = new HashSet<>(a);
        d.removeAll(b);
        return d;
    }

    private static int closedPort() throws java.io.IOException {
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private ShopCheckoutRequest pointsOnly(UUID shopId) {
        ShopCheckoutRequest r = new ShopCheckoutRequest();
        r.setShopId(shopId);
        r.setMsisdn("0712345678");
        r.setPaymentMethod(PaymentMethod.POINTS);
        r.setPointsAmount(new BigDecimal("200.0000"));
        return r;
    }

    private ShopCheckoutRequest withCash(PaymentMethod method, String cash, String points) {
        ShopCheckoutRequest r = new ShopCheckoutRequest();
        r.setShopId(UUID.randomUUID());
        r.setMsisdn("0712345678");
        r.setPaymentMethod(method);
        r.setCashAmount(cash == null ? null : new BigDecimal(cash));
        r.setPointsAmount(points == null ? null : new BigDecimal(points));
        return r;
    }

    @Test
    void shopCheckout_pointsOnly_burnsAndEarnsNothing() {
        LoyaltyServiceClient loyalty = mock(LoyaltyServiceClient.class);
        UUID shopId = UUID.randomUUID();
        UUID merchantId = UUID.randomUUID();
        when(loyalty.shopCheckout(eq(shopId), eq("0712345678"),
                eq(null), eq(new BigDecimal("200.0000")),
                argThat((String r) -> r != null && r.startsWith("SHOP-"))))
                .thenReturn(new LoyaltyServiceClient.CheckoutResult(
                        shopId, merchantId, UUID.randomUUID(), UUID.randomUUID(),
                        BigDecimal.ZERO, new BigDecimal("200.0000"),
                        BigDecimal.ZERO, new BigDecimal("1600.0000"),
                        null, UUID.randomUUID()));

        ResponseEntity<ApiResult<ShopCheckoutResponse>> resp = new PaymentController(
                loyalty, newMetrics(), mock(InnbucksPaymentService.class), mock(innbucks.paymentservice.service.ZimswitchCardPaymentService.class), new innbucks.paymentservice.client.ZimswitchProperties(), mock(innbucks.paymentservice.service.EcocashPaymentService.class), mock(PaymentRecordService.class), mock(PaymentRepository.class)).shopCheckout(pointsOnly(shopId), AUTH_0712345678);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        ShopCheckoutResponse data = resp.getBody().getData();
        assertEquals(PaymentMethod.POINTS, data.getPaymentMethod());
        assertEquals(0, new BigDecimal("200.0000").compareTo(data.getPointsRedeemed()));
        assertEquals(0, BigDecimal.ZERO.compareTo(data.getPointsEarned()));
        assertEquals(merchantId, data.getMerchantId());
        // walletBalanceAfter is intentionally OFF the response — see the field
        // comment on ShopCheckoutResponse. Don't reintroduce an assertion here
        // unless the field is being added back.
        // Auto-generated reference is surfaced on the response so the FE
        // / POS receipt can display it.
        assertNotNull(data.getReference());
        assertTrue(data.getReference().startsWith("SHOP-"));
    }

    @Test
    void shopCheckout_cashOnly_isRefused_andNeverReachesLoyalty() {
        // The cash amount is whatever the customer typed: nothing here collects
        // it and no till records the sale, so it must never earn points.
        LoyaltyServiceClient loyalty = mock(LoyaltyServiceClient.class);

        ResponseEntity<ApiResult<ShopCheckoutResponse>> resp = new PaymentController(
                loyalty, newMetrics(), mock(InnbucksPaymentService.class), mock(innbucks.paymentservice.service.ZimswitchCardPaymentService.class), new innbucks.paymentservice.client.ZimswitchProperties(), mock(innbucks.paymentservice.service.EcocashPaymentService.class), mock(PaymentRecordService.class), mock(PaymentRepository.class))
                .shopCheckout(withCash(PaymentMethod.CASH, "10.00", null), AUTH_0712345678);

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        assertTrue(resp.getBody().getMessage().startsWith("paymentMethod=CASH is not accepted"));
        verifyNoInteractions(loyalty);
    }

    @Test
    void shopCheckout_cashAndPoints_isRefused_andBurnsNothing() {
        LoyaltyServiceClient loyalty = mock(LoyaltyServiceClient.class);

        ResponseEntity<ApiResult<ShopCheckoutResponse>> resp = new PaymentController(
                loyalty, newMetrics(), mock(InnbucksPaymentService.class), mock(innbucks.paymentservice.service.ZimswitchCardPaymentService.class), new innbucks.paymentservice.client.ZimswitchProperties(), mock(innbucks.paymentservice.service.EcocashPaymentService.class), mock(PaymentRecordService.class), mock(PaymentRepository.class))
                .shopCheckout(withCash(PaymentMethod.CASH_AND_POINTS, "10.00", "200.0000"), AUTH_0712345678);

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        assertTrue(resp.getBody().getMessage().startsWith("paymentMethod=CASH_AND_POINTS is not accepted"));
        verifyNoInteractions(loyalty);
    }

    @Test
    void shopCheckout_rejectsPointsMethodWithCashAmount() {
        LoyaltyServiceClient loyalty = mock(LoyaltyServiceClient.class);

        ResponseEntity<ApiResult<ShopCheckoutResponse>> resp = new PaymentController(
                loyalty, newMetrics(), mock(InnbucksPaymentService.class), mock(innbucks.paymentservice.service.ZimswitchCardPaymentService.class), new innbucks.paymentservice.client.ZimswitchProperties(), mock(innbucks.paymentservice.service.EcocashPaymentService.class), mock(PaymentRecordService.class), mock(PaymentRepository.class))
                .shopCheckout(withCash(PaymentMethod.POINTS, "10.00", "200"), AUTH_0712345678);

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        assertEquals("paymentMethod=POINTS requires pointsAmount > 0 and cashAmount must be null/zero",
                resp.getBody().getMessage());
        verifyNoInteractions(loyalty);
    }

    @Test
    void shopCheckout_rejectsPointsMethodWithoutPointsAmount() {
        ResponseEntity<ApiResult<ShopCheckoutResponse>> resp = new PaymentController(
                mock(LoyaltyServiceClient.class), newMetrics(), mock(InnbucksPaymentService.class), mock(innbucks.paymentservice.service.ZimswitchCardPaymentService.class), new innbucks.paymentservice.client.ZimswitchProperties(), mock(innbucks.paymentservice.service.EcocashPaymentService.class), mock(PaymentRecordService.class), mock(PaymentRepository.class))
                .shopCheckout(withCash(PaymentMethod.POINTS, null, null), AUTH_0712345678);

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
    }

    @Test
    void shopCheckout_surfacesLoyaltyServiceErrorVerbatim() {
        LoyaltyServiceClient loyalty = mock(LoyaltyServiceClient.class);
        when(loyalty.shopCheckout(any(), any(), any(), any(), any()))
                .thenThrow(new LoyaltyCheckoutException("merchant is not active; no loyalty operations will run", 400));

        ResponseEntity<ApiResult<ShopCheckoutResponse>> resp = new PaymentController(
                loyalty, newMetrics(), mock(InnbucksPaymentService.class), mock(innbucks.paymentservice.service.ZimswitchCardPaymentService.class), new innbucks.paymentservice.client.ZimswitchProperties(), mock(innbucks.paymentservice.service.EcocashPaymentService.class), mock(PaymentRecordService.class), mock(PaymentRepository.class)).shopCheckout(pointsOnly(UUID.randomUUID()), AUTH_0712345678);

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        assertTrue(resp.getBody().getMessage().contains("merchant is not active"));
    }

    @Test
    void shopCheckout_successIncrementsSuccessOutcomeCounter() {
        LoyaltyServiceClient loyalty = mock(LoyaltyServiceClient.class);
        UUID shopId = UUID.randomUUID();
        when(loyalty.shopCheckout(any(), any(), any(), any(), any()))
                .thenReturn(new LoyaltyServiceClient.CheckoutResult(
                        shopId, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                        BigDecimal.ZERO, new BigDecimal("200"),
                        BigDecimal.ZERO, new BigDecimal("1600"),
                        null, UUID.randomUUID()));
        PaymentMetrics m = newMetrics();
        new PaymentController(
                loyalty, m, mock(InnbucksPaymentService.class), mock(innbucks.paymentservice.service.ZimswitchCardPaymentService.class), new innbucks.paymentservice.client.ZimswitchProperties(), mock(innbucks.paymentservice.service.EcocashPaymentService.class), mock(PaymentRecordService.class), mock(PaymentRepository.class))
                .shopCheckout(pointsOnly(shopId), AUTH_0712345678);

        assertEquals(1.0, counter(m, "outcome", "success", "mode", "points"));
        assertEquals(1L, m.shopCheckoutDuration().count());
    }

    @Test
    void shopCheckout_loyaltyDownIncrementsUnavailableCounter() {
        LoyaltyServiceClient loyalty = mock(LoyaltyServiceClient.class);
        when(loyalty.shopCheckout(any(), any(), any(), any(), any()))
                .thenThrow(new LoyaltyCheckoutException("Unable to reach loyalty-service for checkout", 503));
        PaymentMetrics m = newMetrics();
        new PaymentController(
                loyalty, m, mock(InnbucksPaymentService.class), mock(innbucks.paymentservice.service.ZimswitchCardPaymentService.class), new innbucks.paymentservice.client.ZimswitchProperties(), mock(innbucks.paymentservice.service.EcocashPaymentService.class), mock(PaymentRecordService.class), mock(PaymentRepository.class))
                .shopCheckout(pointsOnly(UUID.randomUUID()), AUTH_0712345678);

        assertEquals(1.0, counter(m, "outcome", "loyalty_unavailable", "mode", "points"));
    }

    @Test
    void shopCheckout_refusedCashIncrementsValidationFailedCounter() {
        PaymentMetrics m = newMetrics();
        new PaymentController(
                mock(LoyaltyServiceClient.class), m, mock(InnbucksPaymentService.class), mock(innbucks.paymentservice.service.ZimswitchCardPaymentService.class), new innbucks.paymentservice.client.ZimswitchProperties(), mock(innbucks.paymentservice.service.EcocashPaymentService.class), mock(PaymentRecordService.class), mock(PaymentRepository.class))
                .shopCheckout(withCash(PaymentMethod.CASH, "10", null), AUTH_0712345678);

        assertEquals(1.0, counter(m, "outcome", "validation_failed", "mode", "cash"));
    }

    private static double counter(PaymentMetrics m, String... tags) {
        // Drill into the SimpleMeterRegistry behind the PaymentMetrics to read
        // payment.shop_checkout{tags...} without going through Prometheus serialization.
        var registry = (io.micrometer.core.instrument.MeterRegistry)
                org.springframework.test.util.ReflectionTestUtils.getField(m, "registry");
        return registry.find("payment.shop_checkout").tags(tags).counter().count();
    }

    @Test
    void processPayment_returns503EquivalentWhenBookingServiceIsUnreachable() {
        UUID bookingId = UUID.randomUUID();
        Fixture f = fixture();
        replayLookup(f, bookingId).thenReturn(Optional.empty());
        when(f.innbucks().processPayment(eq(OrderType.BOOKING), eq(bookingId.toString()), isNull(), isNull()))
                .thenThrow(new BookingConfirmationException(
                        "Unable to reach booking-service to confirm the booking", 503));

        ResponseEntity<ApiResult<PaymentResponse>> resp =
                f.controller().processPayment(paymentFor(bookingId));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, resp.getStatusCode());
        assertTrue(resp.getBody().getMessage().toLowerCase().contains("booking-service"));
    }

    @Test
    void replayOpenCode_instantCheckPaid_returnsSuccessReceiptImmediately() {
        Fixture f = fixture();
        UUID bookingId = UUID.randomUUID();
        Payment open = Payment.builder()
                .id(UUID.randomUUID()).paymentReference("TKT-PMT-" + UUID.randomUUID())
                .bookingId(bookingId).customerMsisdn("+263770000001")
                .amount(new BigDecimal("10.00")).currency("USD")
                .status(Payment.PaymentStatus.TOKEN_ISSUED)
                .innbucksCode("701285660").codeAuthNumber("1616800")
                .codeExpiresAt(java.time.Instant.now().plusSeconds(300))
                .createdAt(java.time.Instant.now().minusSeconds(60))
                .build();
        Payment promoted = Payment.builder()
                .id(open.getId()).paymentReference(open.getPaymentReference())
                .bookingId(bookingId).customerMsisdn("+263770000001")
                .amount(new BigDecimal("10.00")).currency("USD")
                .status(Payment.PaymentStatus.SUCCEEDED)
                .innbucksCode("701285660").codeAuthNumber("1616800")
                .confirmationNumber("INN-20260612-OK")
                .createdAt(open.getCreatedAt())
                .build();
        replayLookup(f, bookingId).thenReturn(Optional.of(open));
        when(f.innbucks().tryResolveOpenCode(open))
                .thenReturn(InnbucksPaymentService.InstantCheckOutcome.PAID);
        when(f.payments().findById(open.getId())).thenReturn(Optional.of(promoted));

        ResponseEntity<ApiResult<PaymentResponse>> resp =
                f.controller().processPayment(paymentFor(bookingId));

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals(PaymentResponse.Status.SUCCESS, resp.getBody().getData().getStatus());
        assertEquals("INN-20260612-OK", resp.getBody().getData().getConfirmationNumber());
        // No fresh payment was started — this resolved the existing one.
        verify(f.innbucks(), never()).processPayment(any(), anyString(), any(), any());
    }

    @Test
    void replayOpenCode_instantCheckExpired_mintsAFreshCodeInTheSameRequest() {
        Fixture f = fixture();
        UUID bookingId = UUID.randomUUID();
        Payment open = Payment.builder()
                .id(UUID.randomUUID()).paymentReference("TKT-PMT-" + UUID.randomUUID())
                .bookingId(bookingId).customerMsisdn("+263770000001")
                .amount(new BigDecimal("10.00")).currency("USD")
                .status(Payment.PaymentStatus.TOKEN_ISSUED)
                .innbucksCode("701285660").codeAuthNumber("1616800")
                .createdAt(java.time.Instant.now().minusSeconds(700))
                .build();
        replayLookup(f, bookingId).thenReturn(Optional.of(open));
        when(f.innbucks().tryResolveOpenCode(open))
                .thenReturn(InnbucksPaymentService.InstantCheckOutcome.EXPIRED);
        when(f.innbucks().processPayment(eq(OrderType.BOOKING), eq(bookingId.toString()), isNull(), isNull()))
                .thenReturn(InnbucksPaymentResponse.builder()
                        .paymentReference("TKT-PMT-" + UUID.randomUUID())
                        .bookingId(bookingId)
                        .status(InnbucksPaymentResponse.Status.PROCESSING)
                        .amountPaid(new BigDecimal("10.00")).currency("USD")
                        .paymentCode("701999111")
                        .build());

        ResponseEntity<ApiResult<PaymentResponse>> resp =
                f.controller().processPayment(paymentFor(bookingId));

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals("701999111", resp.getBody().getData().getPaymentCode(),
                "an upstream-expired code must be replaced with a FRESH one in the same tap");
    }
}
