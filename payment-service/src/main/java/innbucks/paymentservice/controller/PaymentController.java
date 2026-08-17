package innbucks.paymentservice.controller;

import innbucks.paymentservice.client.BookingServiceClient.BookingConfirmationException;
import innbucks.paymentservice.client.LoyaltyServiceClient;
import innbucks.paymentservice.client.LoyaltyServiceClient.LoyaltyCheckoutException;
import innbucks.paymentservice.config.PaymentMetrics;
import innbucks.paymentservice.controller.OrderKeys.OrderKey;
import innbucks.paymentservice.dto.ApiResult;
import innbucks.paymentservice.dto.InnbucksPaymentResponse;
import innbucks.paymentservice.dto.PaymentMethod;
import innbucks.paymentservice.dto.PaymentRequest;
import innbucks.paymentservice.dto.PaymentResponse;
import innbucks.paymentservice.dto.ShopCheckoutRequest;
import innbucks.paymentservice.dto.ShopCheckoutResponse;
import innbucks.paymentservice.entity.Payment;
import innbucks.paymentservice.exception.BadRequestException;
import innbucks.paymentservice.order.OrderType;
import innbucks.paymentservice.repository.PaymentRepository;
import innbucks.paymentservice.service.InnbucksPaymentService;
import innbucks.paymentservice.service.InnbucksPaymentService.InvalidPaymentRequestException;
import innbucks.paymentservice.service.PaymentRecordService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Customer-facing payment endpoints.
 *
 * <ul>
 *   <li>{@code POST /payments} — checkout payment, the FE's public entry.
 *       <b>Collects real money</b> via the InnBucks 2D-code rail (Merchant
 *       API) for ANY product behind an {@code OrderGateway}: ticket bookings
 *       (the historical {@code bookingId} contract, unchanged) and
 *       marketplace orders (additive {@code orderType} + {@code orderRef}).
 *       An InnBucks PAYMENT code is issued for the order's total and the
 *       payer is the phone captured at order creation; the customer approves
 *       it in their own InnBucks app/USSD and the reconciler's poller
 *       confirms the order. Request/response shapes stay stub-compatible;
 *       {@code PROCESSING} is the one additive status value and
 *       {@code paymentCode} / {@code paymentCodeExpiresAt} /
 *       {@code orderType} / {@code orderRef} are additive fields. Idempotent
 *       replay: a double-tap on an already-paid/in-flight order returns that
 *       payment's receipt (with the live code if still awaiting approval),
 *       never an error.</li>
 *   <li>{@code POST /payments/shop-checkout} — pay at a shop with cash,
 *       loyalty points, or a mix. <b>Moves real loyalty points</b> via
 *       loyalty-service (earn on the cash leg, burn on the points leg —
 *       both committed atomically). Wallet operations from
 *       {@link TransfersController} are a separate surface and use real
 *       Oradian-backed money.</li>
 * </ul>
 */
@RestController
@RequestMapping("/payments")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Payments", description = "Customer payment endpoints. POST /payments moves real money via the "
        + "InnBucks Bank API; shop checkout moves real loyalty points.")
public class PaymentController {

    private final LoyaltyServiceClient loyaltyServiceClient;
    private final PaymentMetrics metrics;
    /** PENDING rows older than this with no code are crash leftovers, not in-flight requests. */
    private static final java.time.Duration PENDING_REPLAY_GRACE = java.time.Duration.ofSeconds(30);

    /** This deployment's currency (cell config) — defensive fallback only; the
     *  service always stamps the outcome's currency. Per-cell, never hardcoded USD. */
    @org.springframework.beans.factory.annotation.Value("${innbucks.currency:USD}")
    private String cellCurrency;

    private final InnbucksPaymentService innbucksPaymentService;
    private final innbucks.paymentservice.service.ZimswitchCardPaymentService zimswitchCardPaymentService;
    private final innbucks.paymentservice.client.ZimswitchProperties zimswitchProperties;
    private final PaymentRecordService paymentRecordService;
    private final PaymentRepository paymentRepository;

    @PostMapping
    @Operation(
            summary = "Pay for an order — InnBucks 2D-code (default) or ZimSwitch card",
            description = "Public endpoint (no login required — guest checkout). Identify the order EXACTLY " +
                    "one way: `bookingId` (ticket bookings — the historical contract, unchanged) OR " +
                    "`orderType` + `orderRef` (additive; e.g. `MARKETPLACE` + the `MKT-...` order reference). " +
                    "\n\n**Rail selection (additive):** omit `paymentRail` (or send `INNBUCKS_CODE`) for the " +
                    "historical InnBucks code/QR flow described below. Send `paymentRail=ZIMSWITCH_CARD` to " +
                    "pay by card: the response carries COPYandPAY widget artifacts (`checkoutId`, " +
                    "`checkoutScriptUrl`, `checkoutIntegrity`, `checkoutBrands`, `shopperResultUrl`) — render " +
                    "the script + `<form class=\"paymentWidgets\" data-brands=\"{checkoutBrands}\" " +
                    "action=\"{shopperResultUrl}\">`; card data goes browser→gateway and never touches this " +
                    "API. After the shopper lands back on `shopperResultUrl`, IGNORE its `resourcePath` query " +
                    "parameter and re-POST this endpoint with the same order key — the backend verifies the " +
                    "payment server-side (never trust the redirect as proof) and replies SUCCESS/PROCESSING. " +
                    "One active payment per order across BOTH rails: while an attempt is open on one rail, " +
                    "POSTing with the other returns the open attempt unchanged; switch rails once it lapses. " +
                    "Amount and currency are read server-side from the order. An InnBucks PAYMENT code is " +
                    "issued for the order's total; the customer approves it in their own InnBucks app " +
                    "(Scan-to-Pay or Pay by Code). The normal response is `status=PROCESSING` with " +
                    "`paymentCode`, `paymentCodeExpiresAt` and `paymentQrCode` — the FE renders both the " +
                    "typed code and the InnBucks-rendered QR (base64) on the checkout screen. No out-of-band " +
                    "delivery: the response IS the delivery.\n\n" +
                    "**Branch on `stage`, never on `message`.** `status` is the coarse historical " +
                    "contract and its `PROCESSING` value covers six different situations; `message` is " +
                    "human prose that gets reworded and localised. The additive `stage` field is the " +
                    "machine-readable discriminator, and `fundsCaptured` answers the money question " +
                    "directly:\n\n" +
                    "| `stage` | `fundsCaptured` | meaning |\n" +
                    "|---|---|---|\n" +
                    "| `AWAITING_PAYMENT` | `false` | instrument live — render the code/QR or the card widget |\n" +
                    "| `IN_PROGRESS` | `false` | request in flight, no instrument yet — retry shortly |\n" +
                    "| `INSTRUMENT_EXPIRED` | `null` | our deadline passed, upstream outcome NOT yet resolved — slot still held |\n" +
                    "| `PAYMENT_UNAVAILABLE` | `false` | cannot present an instrument (config/outage) — do NOT auto-retry |\n" +
                    "| `PAYMENT_RECEIVED` | `true` | **money captured**, order confirming — the confident receipt screen |\n" +
                    "| `COMPLETED` | `true` | captured AND confirmed (pairs with `status=SUCCESS`) |\n" +
                    "| `VERIFYING` | `null` | **UNKNOWN** — money may or may not have moved; direct to support |\n\n" +
                    "`fundsCaptured` is deliberately nullable: `null` means we genuinely do not know, and " +
                    "reporting `false` there would tell a customer who may have paid that nothing was " +
                    "charged. Note `INSTRUMENT_EXPIRED` is `null`, not `false` — a client only ever sees " +
                    "it while the reconciler has not concluded the instrument went unpaid (a row proven " +
                    "unpaid goes terminal and is not replayed), and the order's payment slot is still " +
                    "held, so re-POSTing returns the same state rather than minting a fresh instrument. " +
                    "For an unrecognised `stage`, do NOT assume a money answer — read `fundsCaptured` and " +
                    "treat `null` as unknown.\n\n" +
                    "**How the FE knows it's done (status lifecycle):** this endpoint returns `PROCESSING` " +
                    "immediately; it does NOT block until payment. The customer then approves the code in " +
                    "their InnBucks app, and a background poller confirms the order within ~20s. For " +
                    "bookings, **poll the booking** by the `bookingId` it created — " +
                    "`GET /bookings/public/{id}` (guest, no login) or `GET /bookings/{id}` (logged-in) — " +
                    "until its status flips to `CONFIRMED` (carrying the confirmation number); for " +
                    "marketplace orders, poll the order by its `orderRef` until it reports `PAID`. Behind " +
                    "the scenes the InnBucks code moves New → Claimed/Paid (both mean the customer paid) → " +
                    "order confirmed; or New → Expired/Timed Out (unpaid) → the code lapses and the FE can " +
                    "offer Pay again. Stop polling once the order is confirmed, or shortly after " +
                    "`paymentCodeExpiresAt` passes while still unconfirmed.\n\n" +
                    "Replay-safe: paying an already-paid or in-flight order returns that payment's receipt, " +
                    "including the live code + QR while it's still awaiting approval. If the code expires " +
                    "unpaid, the payment closes and POSTing again issues a fresh code."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "Code issued (PROCESSING), or replay of a completed payment (SUCCESS)",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = PaymentResponse.class),
                            examples = {
                                    @ExampleObject(name = "Booking — code issued, awaiting customer approval", value = """
                                            {
                                              "code": "200 OK",
                                              "message": "Approve the payment in your InnBucks app to complete your booking",
                                              "data": {
                                                "transactionId": "f0e1d2c3-4567-890a-bcde-f01234567890",
                                                "bookingId": "a3b9c1d2-1234-5678-9abc-def012345678",
                                                "orderType": "BOOKING",
                                                "orderRef": "a3b9c1d2-1234-5678-9abc-def012345678",
                                                "status": "PROCESSING",
                                                "stage": "AWAITING_PAYMENT",
                                                "fundsCaptured": false,
                                                "amountPaid": 100.00,
                                                "currency": "USD",
                                                "confirmationNumber": null,
                                                "processedAt": "2026-06-11T15:48:00",
                                                "paymentCode": "701285660",
                                                "paymentCodeExpiresAt": "2026-06-11T15:58:00",
                                                "paymentQrCode": "iVBORw0KGgoAAAANSUhEUg..."
                                              }
                                            }
                                            """),
                                    @ExampleObject(name = "Booking — replay after the customer approved", value = """
                                            {
                                              "code": "200 OK",
                                              "message": "Payment processed successfully",
                                              "data": {
                                                "transactionId": "f0e1d2c3-4567-890a-bcde-f01234567890",
                                                "bookingId": "a3b9c1d2-1234-5678-9abc-def012345678",
                                                "orderType": "BOOKING",
                                                "orderRef": "a3b9c1d2-1234-5678-9abc-def012345678",
                                                "status": "SUCCESS",
                                                "stage": "COMPLETED",
                                                "fundsCaptured": true,
                                                "amountPaid": 100.00,
                                                "currency": "USD",
                                                "confirmationNumber": "INN-20260611-AB12CD",
                                                "processedAt": "2026-06-11T15:52:00",
                                                "paymentCode": null,
                                                "paymentCodeExpiresAt": null,
                                                "paymentQrCode": null
                                              }
                                            }
                                            """),
                                    @ExampleObject(name = "Money IS in, order confirming — the confident receipt screen", value = """
                                            {
                                              "code": "200 OK",
                                              "message": "Payment received; your booking is being confirmed",
                                              "data": {
                                                "transactionId": "f0e1d2c3-4567-890a-bcde-f01234567890",
                                                "bookingId": "a3b9c1d2-1234-5678-9abc-def012345678",
                                                "orderType": "BOOKING",
                                                "orderRef": "a3b9c1d2-1234-5678-9abc-def012345678",
                                                "status": "PROCESSING",
                                                "stage": "PAYMENT_RECEIVED",
                                                "fundsCaptured": true,
                                                "amountPaid": 100.00,
                                                "currency": "USD",
                                                "confirmationNumber": null,
                                                "processedAt": "2026-06-11T15:52:00Z"
                                              }
                                            }
                                            """),
                                    @ExampleObject(name = "Outcome UNKNOWN — fundsCaptured is an explicit null, never coerce it to false", value = """
                                            {
                                              "code": "200 OK",
                                              "message": "Your payment is being verified — contact support if this persists",
                                              "data": {
                                                "transactionId": "f0e1d2c3-4567-890a-bcde-f01234567890",
                                                "bookingId": "a3b9c1d2-1234-5678-9abc-def012345678",
                                                "orderType": "BOOKING",
                                                "orderRef": "a3b9c1d2-1234-5678-9abc-def012345678",
                                                "status": "PROCESSING",
                                                "stage": "VERIFYING",
                                                "fundsCaptured": null,
                                                "amountPaid": 100.00,
                                                "currency": "USD",
                                                "confirmationNumber": null,
                                                "processedAt": "2026-06-11T15:52:00Z"
                                              }
                                            }
                                            """),
                                    @ExampleObject(name = "Booking — card checkout issued (request: {\"bookingId\":\"a3b9c1d2-...\",\"paymentRail\":\"ZIMSWITCH_CARD\"})", value = """
                                            {
                                              "code": "200 OK",
                                              "message": "Enter your card details to complete your booking",
                                              "data": {
                                                "transactionId": "f0e1d2c3-4567-890a-bcde-f01234567890",
                                                "bookingId": "a3b9c1d2-1234-5678-9abc-def012345678",
                                                "orderType": "BOOKING",
                                                "orderRef": "a3b9c1d2-1234-5678-9abc-def012345678",
                                                "status": "PROCESSING",
                                                "stage": "AWAITING_PAYMENT",
                                                "fundsCaptured": false,
                                                "amountPaid": 100.00,
                                                "currency": "USD",
                                                "confirmationNumber": null,
                                                "processedAt": "2026-06-11T15:48:00",
                                                "paymentRail": "ZIMSWITCH_CARD",
                                                "checkoutId": "8a82944a4cc25ebf014cc2c782423202",
                                                "checkoutScriptUrl": "https://eu-test.oppwa.com/v1/paymentWidgets.js?checkoutId=8a82944a4cc25ebf014cc2c782423202",
                                                "checkoutIntegrity": "sha384-3phAZzHTYFuLtHT2AzM5PIYjPLGtqcBQXAq7fbQw0QHIhJEQZUJEG52uV6uWBSQE",
                                                "checkoutBrands": "VISA MASTER",
                                                "shopperResultUrl": "https://tickets.example.co.zw/checkout/card-result",
                                                "checkoutExpiresAt": "2026-06-11T16:16:00"
                                              }
                                            }
                                            """),
                                    @ExampleObject(name = "Marketplace order — code issued (request: {\"orderType\":\"MARKETPLACE\",\"orderRef\":\"MKT-4F9A1C22B7D3\"})", value = """
                                            {
                                              "code": "200 OK",
                                              "message": "Approve the payment in your InnBucks app to complete your order",
                                              "data": {
                                                "transactionId": "b7d3e8a1-9c2f-4e5d-8a6b-1f0c9d8e7a65",
                                                "bookingId": null,
                                                "orderType": "MARKETPLACE",
                                                "orderRef": "MKT-4F9A1C22B7D3",
                                                "status": "PROCESSING",
                                                "stage": "AWAITING_PAYMENT",
                                                "fundsCaptured": false,
                                                "amountPaid": 35.50,
                                                "currency": "USD",
                                                "confirmationNumber": null,
                                                "processedAt": "2026-08-05T10:30:00",
                                                "paymentCode": "701442918",
                                                "paymentCodeExpiresAt": "2026-08-05T10:40:00",
                                                "paymentQrCode": "iVBORw0KGgoAAAANSUhEUg..."
                                              }
                                            }
                                            """)
                            }
                    )
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                    description = "Validation error (order identification), a rejected payment (stub error "
                            + "vocabulary: declines surface as 400 + reason), or the product service refused "
                            + "the hold extension",
                    content = @Content(mediaType = "application/json",
                            examples = {
                                    @ExampleObject(name = "No order identified", value = """
                                            {
                                              "code": "400 BAD_REQUEST",
                                              "message": "Provide exactly one of bookingId or orderType + orderRef (orderType and orderRef go together)",
                                              "data": null
                                            }
                                            """),
                                    @ExampleObject(name = "Both shapes supplied", value = """
                                            {
                                              "code": "400 BAD_REQUEST",
                                              "message": "Provide exactly one of bookingId or orderType + orderRef — not both",
                                              "data": null
                                            }
                                            """),
                                    @ExampleObject(name = "Hold expired", value = """
                                            {
                                              "code": "400 BAD_REQUEST",
                                              "message": "Seat hold expired",
                                              "data": null
                                            }
                                            """)
                            })),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
                    description = "Order not found in its product service",
                    content = @Content(mediaType = "application/json",
                            examples = {
                                    @ExampleObject(name = "Unknown booking", value = """
                                            {
                                              "code": "404 NOT_FOUND",
                                              "message": "Booking not found",
                                              "data": null
                                            }
                                            """),
                                    @ExampleObject(name = "Unknown marketplace order", value = """
                                            {
                                              "code": "404 NOT_FOUND",
                                              "message": "Order not found",
                                              "data": null
                                            }
                                            """)
                            })),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
                    description = "The order is not awaiting payment (already paid, cancelled or expired) "
                            + "and no replayable payment row exists",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(name = "Not payable", value = """
                                    {
                                      "code": "409 CONFLICT",
                                      "message": "This order is not awaiting payment — it may already be paid, cancelled or expired",
                                      "data": null
                                    }
                                    """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "503",
                    description = "The product service (booking-service / marketplace-service) is unreachable",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(name = "Downstream down", value = """
                                    {
                                      "code": "503 SERVICE_UNAVAILABLE",
                                      "message": "Unable to reach booking-service to confirm the booking",
                                      "data": null
                                    }
                                    """)))
    })
    public ResponseEntity<ApiResult<PaymentResponse>> processPayment(
            @Valid @RequestBody PaymentRequest request
    ) {
        // Exactly one of bookingId / (orderType + orderRef); bookingId
        // implies BOOKING — the historical FE contract, unchanged.
        OrderKey key;
        try {
            key = OrderKeys.resolve(request.getBookingId(), request.getOrderType(), request.getOrderRef());
        } catch (BadRequestException e) {
            return error(HttpStatus.BAD_REQUEST, e.getMessage());
        }
        log.info("POST /payments orderType={} orderRef={}", key.type(), key.ref());

        // Replay semantics: an already-paid or in-flight order returns the
        // existing payment's receipt — a double-tap must never error (the old
        // stub re-confirmed idempotently; this is the real-money equivalent).
        var existing = paymentRepository.findFirstByOrderTypeAndOrderRefAndStatusInOrderByCreatedAtDesc(
                key.type(), key.ref(), PaymentRecordService.ACTIVE_OR_SUCCEEDED);
        if (existing.isPresent()) {
            Payment p = existing.get();
            if (isOrphanedPending(p)) {
                // A PENDING row past the in-flight window with no code ever
                // recorded is a crash/outage leftover (we died between opening
                // the row and recording the generate outcome). Replaying it
                // would show the customer PROCESSING with nothing to pay —
                // forever. Close it FAILED and run a fresh attempt right here.
                log.warn("POST /payments replacing orphaned PENDING orderType={} orderRef={} paymentReference={} ageSec={}",
                        key.type(), key.ref(), p.getPaymentReference(),
                        java.time.Duration.between(p.getCreatedAt(), java.time.Instant.now()).toSeconds());
                paymentRecordService.markFailed(p.getId(), "stale_pending",
                        "Orphaned PENDING row (no code recorded) replaced by a fresh attempt on customer retry");
            } else if (p.getStatus() == Payment.PaymentStatus.TOKEN_ISSUED
                    && p.getPaymentRail() == innbucks.paymentservice.entity.PaymentRail.ZIMSWITCH_CARD
                    && p.getCheckoutId() != null) {
                // Card twin of the instant check below: the shopper just
                // landed back on the result page (or refreshed) — ask the
                // gateway NOW, inside the per-checkout throttle share.
                var cardCheck = zimswitchCardPaymentService.resolveOpenCheckout(
                        p, zimswitchProperties.getInstantCheckMinGap());
                if (cardCheck == innbucks.paymentservice.service.ZimswitchCardPaymentService.CardCheckOutcome.PAID) {
                    Payment resolved = paymentRepository.findById(p.getId()).orElse(p);
                    log.info("POST /payments card instant-check PAID orderType={} orderRef={} paymentReference={}",
                            key.type(), key.ref(), p.getPaymentReference());
                    return toReplayResponse(resolved, key);
                }
                if (cardCheck == innbucks.paymentservice.service.ZimswitchCardPaymentService.CardCheckOutcome.EXPIRED) {
                    // Row just went terminal (EXPIRED, slot freed) — fall
                    // through and start a FRESH attempt in this same request.
                    log.info("POST /payments card instant-check EXPIRED orderType={} orderRef={} — starting fresh",
                            key.type(), key.ref());
                } else {
                    // RE-READ, do not reuse `p`. A PENDING verdict does NOT
                    // mean the row is unchanged: an echo mismatch on a PAID
                    // read parks the row IN_DOUBT and still reports PENDING
                    // (the money outcome is unknown, so it is not a PAID
                    // result). markInDoubt runs REQUIRES_NEW on its own loaded
                    // instance, so this `p` still says TOKEN_ISSUED — replaying
                    // it would answer stage=AWAITING_PAYMENT + fundsCaptured=
                    // false and re-render the card widget for a payment that
                    // may already have taken the customer's money. Both harms
                    // point the wrong way: a false "you were not charged", and
                    // an invitation to pay a second time.
                    Payment afterCheck = paymentRepository.findById(p.getId()).orElse(p);
                    log.info("POST /payments card replay after instant check orderType={} orderRef={} paymentReference={} status={}",
                            key.type(), key.ref(), p.getPaymentReference(), afterCheck.getStatus());
                    return toReplayResponse(afterCheck, key);
                }
            } else if (p.getStatus() == Payment.PaymentStatus.TOKEN_ISSUED
                    && p.getInnbucksCode() != null) {
                // Customer-triggered instant check ("I've paid" / page refresh):
                // ask InnBucks NOW instead of replaying blindly — confirmation
                // lands ~1s after the customer tells us, not a poll cycle later.
                var check = innbucksPaymentService.tryResolveOpenCode(p);
                if (check == InnbucksPaymentService.InstantCheckOutcome.PAID) {
                    Payment resolved = paymentRepository.findById(p.getId()).orElse(p);
                    log.info("POST /payments instant-check PAID orderType={} orderRef={} paymentReference={}",
                            key.type(), key.ref(), p.getPaymentReference());
                    return toReplayResponse(resolved, key);
                }
                if (check == InnbucksPaymentService.InstantCheckOutcome.EXPIRED) {
                    // Row just went terminal (EXPIRED, slot freed) — fall
                    // through and mint a FRESH code in this same request.
                    log.info("POST /payments instant-check EXPIRED orderType={} orderRef={} — minting a fresh code",
                            key.type(), key.ref());
                } else {
                    // Re-read for the same reason as the card branch above.
                    // DEFENSIVE here rather than a live bug: today
                    // tryResolveOpenCode only mutates on PAID/EXPIRED and
                    // leaves the row untouched when it reports PENDING. But
                    // that was equally true of the card rail until a
                    // mutating-and-still-PENDING path (echo mismatch → park
                    // IN_DOUBT) was added, and the failure it produces is a
                    // false "you were not charged" on a paid row. One query
                    // on a path that just made a network call is a cheap way
                    // to make the branch correct by construction.
                    Payment afterCheck = paymentRepository.findById(p.getId()).orElse(p);
                    log.info("POST /payments replay after instant check (still pending upstream) orderType={} orderRef={} paymentReference={} status={}",
                            key.type(), key.ref(), p.getPaymentReference(), afterCheck.getStatus());
                    return toReplayResponse(afterCheck, key);
                }
            } else {
                log.info("POST /payments replay orderType={} orderRef={} existing paymentReference={} status={}",
                        key.type(), key.ref(), p.getPaymentReference(), p.getStatus());
                return toReplayResponse(p, key);
            }
        }

        // The payer is the order's phone — captured at order creation (JWT or
        // guest flow), resolved from the gateway snapshot inside the service.
        // The FE never supplies payment credentials.
        //
        // Rail dispatch (additive contract): omitted/null = the historical
        // InnBucks 2D-code flow; ZIMSWITCH_CARD = COPYandPAY widget checkout.
        innbucks.paymentservice.entity.PaymentRail rail =
                request.getPaymentRail() != null ? request.getPaymentRail()
                        : innbucks.paymentservice.entity.PaymentRail.INNBUCKS_CODE;
        InnbucksPaymentResponse outcome;
        try {
            if (rail == innbucks.paymentservice.entity.PaymentRail.ZIMSWITCH_CARD) {
                return toCardResponse(
                        zimswitchCardPaymentService.startCheckout(key.type(), key.ref()), key);
            }
            outcome = innbucksPaymentService.processPayment(key.type(), key.ref(), null, null);
        } catch (BookingConfirmationException e) {
            HttpStatus status = HttpStatus.resolve(e.getStatusCode());
            if (status == null) status = HttpStatus.BAD_GATEWAY;
            return error(status, e.getMessage());
        } catch (InvalidPaymentRequestException e) {
            // 409 (already paying — race with another tap) degrades to replay.
            if (e.getStatusCode() == 409) {
                return paymentRepository.findFirstByOrderTypeAndOrderRefAndStatusInOrderByCreatedAtDesc(
                                key.type(), key.ref(), PaymentRecordService.ACTIVE_OR_SUCCEEDED)
                        .map(p -> toReplayResponse(p, key))
                        .orElseGet(() -> error(HttpStatus.CONFLICT, e.getMessage()));
            }
            HttpStatus status = HttpStatus.resolve(e.getStatusCode());
            if (status == null) status = HttpStatus.UNPROCESSABLE_ENTITY;
            // Keep the stub's error vocabulary: declines surface as 400 with
            // the human-readable reason (e.g. insufficient balance). Compare
            // by value — Framework 7 renamed 422's constant.
            if (status.value() == 422) status = HttpStatus.BAD_REQUEST;
            return error(status, e.getMessage());
        }

        return toStubShapedResponse(outcome, key);
    }

    /** Map the real-money outcome onto the stub's exact response contract. */
    private ResponseEntity<ApiResult<PaymentResponse>> toStubShapedResponse(
            InnbucksPaymentResponse outcome, OrderKey key) {
        PaymentResponse.Status status = switch (outcome.getStatus()) {
            case SUCCESS -> PaymentResponse.Status.SUCCESS;
            case PROCESSING -> PaymentResponse.Status.PROCESSING;
            case FAILED -> PaymentResponse.Status.FAILED;
        };
        if (status == PaymentResponse.Status.FAILED) {
            // Stub error vocabulary: non-200 + message, data null.
            String message = outcome.getUpstreamMessage() != null
                    ? outcome.getUpstreamMessage() : "Payment was rejected";
            return error(HttpStatus.BAD_REQUEST, message);
        }
        PaymentResponse response = PaymentResponse.builder()
                .transactionId(outcome.getPaymentId())
                .bookingId(outcome.getBookingId())
                .orderType(key.type())
                .orderRef(key.ref())
                .status(status)
                .amountPaid(outcome.getAmountPaid())
                // Currency is always the order/cell's — never client-supplied.
                .currency(outcome.getCurrency() != null ? outcome.getCurrency() : cellCurrency)
                .confirmationNumber(outcome.getConfirmationNumber())
                .processedAt(LocalDateTime.now(ZoneOffset.UTC))
                // This method serves the 2D-code rail exclusively (the card
                // branch returns via toCardResponse), so the rail is known.
                // It MUST be stated: clients branch on the rail they RECEIVE,
                // not the one they asked for, and omitting it here left the
                // DEFAULT payment path as the only response carrying no rail
                // — an FE with explicit per-rail arms renders nothing.
                .paymentRail(innbucks.paymentservice.entity.PaymentRail.INNBUCKS_CODE)
                // Fresh outcome: SUCCESS means captured + confirmed; the only
                // other value reaching here is PROCESSING with a live code
                // (FAILED returns via error() above), i.e. nothing charged yet.
                .stage(status == PaymentResponse.Status.SUCCESS
                        ? PaymentResponse.Stage.COMPLETED
                        : PaymentResponse.Stage.AWAITING_PAYMENT)
                .paymentCode(outcome.getPaymentCode())
                .paymentCodeExpiresAt(outcome.getPaymentCodeExpiresAt())
                .paymentQrCode(outcome.getPaymentQrCode())
                .build();
        String message = status == PaymentResponse.Status.SUCCESS
                ? "Payment processed successfully"
                : (outcome.getUpstreamMessage() != null ? outcome.getUpstreamMessage()
                        : "Payment received; confirmation will follow shortly");
        log.info("Payment processed transactionId={} orderType={} orderRef={} status={} confirmation={} amountPaid={}",
                response.getTransactionId(), key.type(), key.ref(), status,
                response.getConfirmationNumber(), response.getAmountPaid());
        return ResponseEntity.ok(ApiResult.ok(message, response));
    }

    /** Map a fresh card-checkout outcome onto the stub's response contract. */
    private ResponseEntity<ApiResult<PaymentResponse>> toCardResponse(
            innbucks.paymentservice.service.ZimswitchCardPaymentService.CardCheckoutOutcome outcome,
            OrderKey key) {
        if (outcome.failed()) {
            // Stub error vocabulary: non-200 + reason, data null (same as a
            // 2D-code refusal).
            String message = outcome.upstreamMessage() != null
                    ? outcome.upstreamMessage() : "Payment was rejected";
            return error(HttpStatus.BAD_REQUEST, message);
        }
        Payment p = outcome.payment();
        String noun = key.type() == OrderType.BOOKING ? "booking" : "order";
        PaymentResponse response = PaymentResponse.builder()
                .transactionId(p.getId())
                .bookingId(p.getBookingId())
                .orderType(key.type())
                .orderRef(key.ref())
                .status(PaymentResponse.Status.PROCESSING)
                .amountPaid(p.getAmount())
                .currency(p.getCurrency() != null ? p.getCurrency() : cellCurrency)
                .processedAt(LocalDateTime.now(ZoneOffset.UTC))
                .paymentRail(innbucks.paymentservice.entity.PaymentRail.ZIMSWITCH_CARD)
                // A freshly prepared checkout is always awaiting card entry —
                // preparing one moves no money.
                .stage(PaymentResponse.Stage.AWAITING_PAYMENT)
                .checkoutId(outcome.checkoutId())
                .checkoutScriptUrl(outcome.widgetScriptUrl())
                .checkoutIntegrity(outcome.checkoutIntegrity())
                .checkoutBrands(outcome.brands())
                .shopperResultUrl(outcome.shopperResultUrl())
                .checkoutExpiresAt(outcome.checkoutExpiresAt() == null ? null
                        : LocalDateTime.ofInstant(outcome.checkoutExpiresAt(), ZoneOffset.UTC))
                .build();
        log.info("Card checkout issued transactionId={} orderType={} orderRef={} checkoutId={}",
                response.getTransactionId(), key.type(), key.ref(), outcome.checkoutId());
        return ResponseEntity.ok(ApiResult.ok(
                "Enter your card details to complete your " + noun, response));
    }

    private ResponseEntity<ApiResult<PaymentResponse>> toReplayResponse(Payment p, OrderKey key) {
        PaymentResponse.Status status = switch (p.getStatus()) {
            case SUCCEEDED -> PaymentResponse.Status.SUCCESS;
            default -> PaymentResponse.Status.PROCESSING;
        };
        String noun = key.type() == OrderType.BOOKING ? "booking" : "order";
        // Only a LIVE awaiting-approval code is re-surfaced (page-refresh
        // recovery). A code past its local deadline is never advertised
        // again — the customer would scan/type a dead code; the poller
        // resolves the row (Expired upstream frees the slot within one
        // interval) and the next Pay tap mints a fresh one. Paid-but-
        // unconfirmed rows keep their code hidden too: that code is spent.
        boolean cardRow = p.getPaymentRail() == innbucks.paymentservice.entity.PaymentRail.ZIMSWITCH_CARD;
        boolean codeStillLive = p.getCodeExpiresAt() == null
                || p.getCodeExpiresAt().isAfter(java.time.Instant.now());
        boolean hasOpenInstrument = p.getStatus() == Payment.PaymentStatus.TOKEN_ISSUED
                && codeStillLive
                && (cardRow ? p.getCheckoutId() != null : p.getInnbucksCode() != null);
        // Card rows re-surface the SAME open checkout (the gateway's
        // documented reuse model — reload/back-button/declined-retry all
        // re-render one checkout); code rows re-surface the live code + QR.
        // A null here means the artifacts exist but are not renderable on
        // this cell (unusable shopperResultUrl) — treat the row as NOT
        // awaiting approval so we never advertise a dead payment form.
        var cardArtifacts = cardRow && hasOpenInstrument
                ? zimswitchCardPaymentService.replayOpenCheckout(p) : null;
        boolean awaitingApproval = hasOpenInstrument && (!cardRow || cardArtifacts != null);
        // ONE derivation of the machine-readable state, reused for the prose
        // below so the two can never disagree. Ordered most-specific first;
        // the fallthrough is deliberately VERIFYING (unknown), never a
        // confident "nothing was charged" — see PaymentResponse.Stage.
        PaymentResponse.Stage stage;
        if (p.getStatus() == Payment.PaymentStatus.SUCCEEDED) {
            stage = PaymentResponse.Stage.COMPLETED;
        } else if (awaitingApproval) {
            stage = PaymentResponse.Stage.AWAITING_PAYMENT;
        } else if (cardRow && hasOpenInstrument) {
            // Live checkout we cannot render on this cell.
            stage = PaymentResponse.Stage.PAYMENT_UNAVAILABLE;
        } else if (p.getStatus() == Payment.PaymentStatus.TOKEN_ISSUED) {
            stage = PaymentResponse.Stage.INSTRUMENT_EXPIRED;
        } else if (p.getStatus() == Payment.PaymentStatus.COMPLETED_UNCONFIRMED) {
            // The money HAS moved — the one PROCESSING state that earns a
            // confident "payment received" screen.
            stage = PaymentResponse.Stage.PAYMENT_RECEIVED;
        } else if (p.getStatus() == Payment.PaymentStatus.PENDING) {
            // Row opened before the upstream call. No money can move on
            // either rail without customer action (code approval / card
            // entry), so "not captured" is safe here.
            stage = PaymentResponse.Stage.IN_PROGRESS;
        } else {
            // IN_DOUBT, plus the reserved states that have no writer. Money
            // may or may not have moved; admitting that is the only honest
            // answer and the only safe one.
            stage = PaymentResponse.Stage.VERIFYING;
        }
        PaymentResponse response = PaymentResponse.builder()
                .stage(stage)
                .transactionId(p.getId())
                .bookingId(p.getBookingId())
                .orderType(key.type())
                .orderRef(key.ref())
                .status(status)
                .amountPaid(p.getAmount())
                .currency(p.getCurrency())
                .confirmationNumber(p.getConfirmationNumber())
                .processedAt(LocalDateTime.now(ZoneOffset.UTC))
                .paymentRail(p.getPaymentRail())
                .paymentCode(!cardRow && awaitingApproval ? p.getInnbucksCode() : null)
                .paymentCodeExpiresAt(!cardRow && awaitingApproval && p.getCodeExpiresAt() != null
                        ? LocalDateTime.ofInstant(p.getCodeExpiresAt(), ZoneOffset.UTC) : null)
                .paymentQrCode(!cardRow && awaitingApproval ? p.getCodeQrBase64() : null)
                .checkoutId(cardArtifacts != null ? cardArtifacts.checkoutId() : null)
                .checkoutScriptUrl(cardArtifacts != null ? cardArtifacts.widgetScriptUrl() : null)
                .checkoutIntegrity(cardArtifacts != null ? cardArtifacts.checkoutIntegrity() : null)
                .checkoutBrands(cardArtifacts != null ? cardArtifacts.brands() : null)
                .shopperResultUrl(cardArtifacts != null ? cardArtifacts.shopperResultUrl() : null)
                .checkoutExpiresAt(cardArtifacts != null && p.getCodeExpiresAt() != null
                        ? LocalDateTime.ofInstant(p.getCodeExpiresAt(), ZoneOffset.UTC) : null)
                .build();
        // Prose for humans, derived FROM the stage above — never the other way
        // round. Clients must branch on `stage`, never on this text: it is
        // reworded freely and will be localised.
        String message = switch (stage) {
            case COMPLETED -> "Payment processed successfully";
            case AWAITING_PAYMENT -> cardRow
                    ? "Enter your card details to complete your " + noun
                    : "Approve the payment in your InnBucks app to complete your " + noun;
            // "Expired — tap Pay again" would be a lie AND a loop here: the row
            // still holds the order's slot, so a retry returns this same state.
            // The ERROR log + card_resolution{outcome=replay_unrenderable}
            // counter are what actually get an operator to fix the config.
            case PAYMENT_UNAVAILABLE -> "Card payment is temporarily unavailable — please try again shortly "
                    + "or contact support if this persists";
            case INSTRUMENT_EXPIRED -> cardRow
                    ? "Your previous card checkout expired — tap Pay again to start a new one"
                    : "Your previous payment code expired — tap Pay again in a moment to get a fresh one";
            case PAYMENT_RECEIVED -> "Payment received; your " + noun + " is being confirmed";
            case IN_PROGRESS -> "Your payment is already being processed — please retry in a moment";
            case VERIFYING -> "Your payment is being verified — contact support if this persists";
        };
        return ResponseEntity.ok(ApiResult.ok(message, response));
    }

    /**
     * PENDING + no code + older than the in-flight window = a crash leftover,
     * not a concurrent request (generate's read timeout is 10s; 30s is
     * comfortably past any genuine in-flight attempt). Younger PENDING rows
     * replay with an honest "being processed" so a true race never
     * double-generates.
     */
    private static boolean isOrphanedPending(Payment p) {
        return p.getStatus() == Payment.PaymentStatus.PENDING
                && p.getInnbucksCode() == null
                && p.getCreatedAt() != null
                && p.getCreatedAt().isBefore(java.time.Instant.now().minus(PENDING_REPLAY_GRACE));
    }

    // transactionIdFrom(paymentReference) was removed. It parsed a UUID out of
    // the legacy TKT-PMT-<uuid> reference, but SettlementReference has since
    // emitted TKZ-<TAG>-<12 hex> whenever a settlement tag exists (i.e. the
    // normal case) — which has no UUID in it, so the parse threw and the
    // catch returned UUID.randomUUID(). The first response therefore carried a
    // RANDOM transactionId that changed on every call and never matched the
    // replay response's (which used the ledger row id). Every path now echoes
    // the row id, which is what the field was always documented to be: a
    // stable receipt id.

    private static ResponseEntity<ApiResult<PaymentResponse>> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(
                ApiResult.<PaymentResponse>builder()
                        .code(status.value() + " " + status.name())
                        .message(message)
                        .data(null)
                        .build());
    }

    @PostMapping("/shop-checkout")
    @Operation(
            summary = "Pay at a shop (cash / points / mixed)",
            description = "Authenticated endpoint. Delegates to loyalty-service's internal shop-checkout to " +
                    "move REAL loyalty points: the cash portion earns points per the merchant's loyalty rules " +
                    "(the existing earn rule + any active campaign multiplier); the points portion is burned " +
                    "from the customer's main wallet. Both legs commit atomically inside loyalty-service. " +
                    "The cash amount itself is not collected here — it is reported informationally (presumably " +
                    "settled at the shop counter). " +
                    "Set the amounts according to `paymentMethod`: CASH → only `cashAmount`; POINTS → only " +
                    "`pointsAmount`; CASH_AND_POINTS → both."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "Checkout complete",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ShopCheckoutResponse.class),
                            examples = @ExampleObject(name = "Cash + points", value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Shop checkout processed successfully",
                                      "data": {
                                        "transactionId": "f0e1d2c3-4567-890a-bcde-f01234567890",
                                        "shopId": "5b1c2d3e-4567-890a-bcde-f01234567890",
                                        "merchantId": "b4c0d2e3-2345-6789-abcd-ef0123456789",
                                        "msisdn": "0712345678",
                                        "paymentMethod": "CASH_AND_POINTS",
                                        "cashAmount": 10.00,
                                        "pointsRedeemed": 200.0000,
                                        "pointsEarned": 12.5000,
                                        "processedAt": "2026-05-14T10:30:00",
                                        "reference": "SHOP-7c9e6679-7425-40de-944b-e07fc1f90ae7"
                                      }
                                    }
                                    """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                    description = "Validation failure, amounts inconsistent with paymentMethod, or " +
                            "loyalty-service rejected the call (inactive shop/merchant, insufficient balance, etc.)",
                    content = @Content(mediaType = "application/json",
                            examples = {
                                    @ExampleObject(name = "Amounts inconsistent", value = """
                                            {
                                              "code": "400 BAD_REQUEST",
                                              "message": "paymentMethod=CASH requires cashAmount > 0 and pointsAmount must be null/zero",
                                              "data": null
                                            }
                                            """),
                                    @ExampleObject(name = "Loyalty rejection", value = """
                                            {
                                              "code": "400 BAD_REQUEST",
                                              "message": "merchant is not active; no loyalty operations will run",
                                              "data": null
                                            }
                                            """)
                            })),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
                    description = "Shop not found in loyalty-service",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(name = "Unknown shop", value = """
                                    {
                                      "code": "404 NOT_FOUND",
                                      "message": "shop not found",
                                      "data": null
                                    }
                                    """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "503",
                    description = "loyalty-service unreachable",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(name = "Loyalty down", value = """
                                    {
                                      "code": "503 SERVICE_UNAVAILABLE",
                                      "message": "Unable to reach loyalty-service for checkout",
                                      "data": null
                                    }
                                    """)))
    })
    public ResponseEntity<ApiResult<ShopCheckoutResponse>> shopCheckout(
            @Valid @RequestBody ShopCheckoutRequest request,
            Authentication authentication
    ) {
        // CRITICAL: derive the customer's MSISDN from the authenticated
        // principal, NOT from request.getMsisdn(). The previous version
        // trusted the body field, letting any caller burn any other
        // customer's loyalty points by supplying their phone. JwtFilter
        // pins the JWT's `phoneNumber` claim into authentication.getName().
        // Any body-supplied msisdn is now silently ignored.
        String msisdn = (authentication == null) ? null : authentication.getName();
        if (msisdn == null || msisdn.isBlank()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(
                    ApiResult.<ShopCheckoutResponse>builder()
                            .code("401 UNAUTHORIZED")
                            .message("Authenticated customer MSISDN not available")
                            .data(null)
                            .build());
        }
        if (request.getMsisdn() != null && !request.getMsisdn().isBlank()
                && !request.getMsisdn().equals(msisdn)) {
            log.warn("Ignoring body-supplied msisdn on /payments/shop-checkout (token wins) " +
                    "shopId={} tokenMsisdn={} bodyMsisdn={}",
                    request.getShopId(),
                    innbucks.paymentservice.util.MsisdnMasking.mask(msisdn),
                    innbucks.paymentservice.util.MsisdnMasking.mask(request.getMsisdn()));
        }

        log.info("POST /payments/shop-checkout shopId={} msisdn={} method={} cash={} points={}",
                request.getShopId(),
                innbucks.paymentservice.util.MsisdnMasking.mask(msisdn),
                request.getPaymentMethod(),
                request.getCashAmount(), request.getPointsAmount());

        String mode = paymentModeTag(request.getPaymentMethod());
        long startNanos = System.nanoTime();

        try {
            validateAmounts(request);
        } catch (BadRequestException e) {
            // validateAmounts now throws the typed BadRequestException
            // (was IllegalArgumentException). We catch it here so the
            // metrics counter still ticks the validation_failed branch —
            // letting the exception propagate to GlobalExceptionHandler
            // would still return 400 but miss the per-mode metric. Same
            // response shape either way; the catch is for instrumentation.
            metrics.incShopCheckout("validation_failed", mode);
            metrics.shopCheckoutDuration().record(System.nanoTime() - startNanos, java.util.concurrent.TimeUnit.NANOSECONDS);
            return ResponseEntity.badRequest().body(
                    ApiResult.<ShopCheckoutResponse>builder()
                            .code("400 BAD_REQUEST")
                            .message(e.getMessage())
                            .data(null)
                            .build());
        }

        // Server-owned reference (per-merchant idempotency on the loyalty PURCHASE
        // row). The FE/POS no longer supplies one — this mirrors the
        // TKT-SMS-<uuid> auto-fill convention used by SmsNotificationClient.
        String reference = "SHOP-" + UUID.randomUUID();

        LoyaltyServiceClient.CheckoutResult result;
        try {
            result = loyaltyServiceClient.shopCheckout(
                    request.getShopId(), msisdn,
                    request.getCashAmount(), request.getPointsAmount(),
                    reference);
        } catch (LoyaltyCheckoutException e) {
            HttpStatus status = HttpStatus.resolve(e.getStatusCode());
            if (status == null) status = HttpStatus.BAD_GATEWAY;
            // 503 = loyalty unreachable (network); anything else = loyalty
            // refused the call (bad shop, inactive merchant, insufficient balance).
            String outcome = status == HttpStatus.SERVICE_UNAVAILABLE ? "loyalty_unavailable" : "loyalty_rejected";
            metrics.incShopCheckout(outcome, mode);
            metrics.shopCheckoutDuration().record(System.nanoTime() - startNanos, java.util.concurrent.TimeUnit.NANOSECONDS);
            log.warn("Shop checkout failed shopId={} status={} reason={}",
                    request.getShopId(), status.value(), e.getMessage());
            return ResponseEntity.status(status).body(
                    ApiResult.<ShopCheckoutResponse>builder()
                            .code(status.value() + " " + status.name())
                            .message(e.getMessage())
                            .data(null)
                            .build());
        }

        ShopCheckoutResponse response = ShopCheckoutResponse.builder()
                .transactionId(UUID.randomUUID())
                .shopId(result.shopId())
                .merchantId(result.merchantId())
                .msisdn(msisdn)
                .paymentMethod(request.getPaymentMethod())
                .cashAmount(result.cashAmount())
                .pointsRedeemed(result.pointsRedeemed())
                .pointsEarned(result.pointsEarned())
                // walletBalanceAfter is intentionally NOT propagated to the response.
                // See the ShopCheckoutResponse field comment for the rationale —
                // keeping the balance off the customer-facing API stops POS systems
                // from printing it on the receipt.
                .processedAt(LocalDateTime.now(ZoneOffset.UTC))
                .reference(reference)
                .build();

        metrics.incShopCheckout("success", mode);
        metrics.shopCheckoutDuration().record(System.nanoTime() - startNanos, java.util.concurrent.TimeUnit.NANOSECONDS);
        // Log the post-transaction balance (from the loyalty checkout result,
        // not from the response — the field is intentionally off the response).
        // Server logs are an operational audit trail, not a customer artefact.
        log.info("Shop checkout processed transactionId={} shopId={} reference={} pointsEarned={} pointsRedeemed={} balance={}",
                response.getTransactionId(), response.getShopId(), reference,
                response.getPointsEarned(), response.getPointsRedeemed(),
                result.walletBalanceAfter());
        return ResponseEntity.ok(ApiResult.ok("Shop checkout processed successfully", response));
    }

    private static String paymentModeTag(PaymentMethod method) {
        if (method == null) return "unknown";
        return switch (method) {
            case CASH -> "cash";
            case POINTS -> "points";
            case CASH_AND_POINTS -> "mixed";
        };
    }

    /**
     * Cross-field validation between {@code paymentMethod} and the two amount
     * fields — bean validation can't express this on its own.
     */
    private static void validateAmounts(ShopCheckoutRequest r) {
        boolean cash = r.getCashAmount() != null && r.getCashAmount().signum() > 0;
        boolean points = r.getPointsAmount() != null && r.getPointsAmount().signum() > 0;
        switch (r.getPaymentMethod()) {
            case CASH -> {
                if (!cash || points) {
                    throw new BadRequestException(
                            "paymentMethod=CASH requires cashAmount > 0 and pointsAmount must be null/zero");
                }
            }
            case POINTS -> {
                if (!points || cash) {
                    throw new BadRequestException(
                            "paymentMethod=POINTS requires pointsAmount > 0 and cashAmount must be null/zero");
                }
            }
            case CASH_AND_POINTS -> {
                if (!cash || !points) {
                    throw new BadRequestException(
                            "paymentMethod=CASH_AND_POINTS requires both cashAmount > 0 and pointsAmount > 0");
                }
            }
        }
    }

}
