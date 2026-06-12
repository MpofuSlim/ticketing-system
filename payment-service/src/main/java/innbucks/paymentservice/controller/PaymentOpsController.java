package innbucks.paymentservice.controller;

import innbucks.paymentservice.dto.ApiResult;
import innbucks.paymentservice.entity.Payment;
import innbucks.paymentservice.entity.Payment.PaymentStatus;
import innbucks.paymentservice.entity.ReconRun;
import innbucks.paymentservice.reconciliation.SettlementReconciliationJob;
import innbucks.paymentservice.repository.PaymentRepository;
import innbucks.paymentservice.repository.ReconRunRepository;
import innbucks.paymentservice.util.MsisdnMasking;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Internal back-office endpoints (operations / finance), NOT customer-facing.
 * Defence in depth per the house "internal endpoints — three files must
 * agree" rule:
 * <ol>
 *   <li>this controller enforces the {@code X-Internal-Token} shared secret
 *       with a constant-time compare;</li>
 *   <li>{@code SecurityConfig} permitAlls {@code /payments/internal/**} so
 *       the token check here is what runs (not a JWT 401);</li>
 *   <li>the api-gateway's {@code payment-internal-deny} route blocks the
 *       path at the edge, so it's unreachable from the public internet.</li>
 * </ol>
 *
 * <ul>
 *   <li>{@code GET /payments/internal/exceptions} — the operator workbasket:
 *       every ledger row that needs a HUMAN, with ageing buckets.</li>
 *   <li>{@code GET /payments/internal/recon-runs} — settlement-reconciliation
 *       history (the morning report).</li>
 *   <li>{@code POST /payments/internal/recon/run} — trigger a recon run for
 *       a given UTC day on demand (defaults to yesterday).</li>
 * </ul>
 */
@RestController
@RequestMapping("/payments/internal")
@Slf4j
@Tag(name = "Payments (internal ops)", description = "Back-office workbasket + settlement reconciliation. "
        + "X-Internal-Token gated; blocked at the api-gateway edge.")
public class PaymentOpsController {

    private static final String INTERNAL_TOKEN_HEADER = "X-Internal-Token";
    private static final int PAGE = 200;
    /** TOKEN_ISSUED rows this far past expiry are stuck, not merely slow. */
    private static final Duration STUCK_CODE_SLACK = Duration.ofMinutes(5);

    private final PaymentRepository paymentRepository;
    private final ReconRunRepository reconRunRepository;
    private final SettlementReconciliationJob settlementReconciliationJob;
    private final String expectedInternalToken;

    public PaymentOpsController(PaymentRepository paymentRepository,
                                ReconRunRepository reconRunRepository,
                                SettlementReconciliationJob settlementReconciliationJob,
                                @Value("${innbucks.internal-api-token:}") String expectedInternalToken) {
        this.paymentRepository = paymentRepository;
        this.reconRunRepository = reconRunRepository;
        this.settlementReconciliationJob = settlementReconciliationJob;
        this.expectedInternalToken = expectedInternalToken;
    }

    // ------------------------------------------------------------ exceptions

    public enum AgeBucket { UNDER_24H, H24_TO_72H, OVER_72H }

    /** One workbasket item — everything an operator needs to start digging. */
    public record ExceptionItem(
            UUID paymentId,
            String paymentReference,
            UUID bookingId,
            String status,
            BigDecimal amount,
            String currency,
            String msisdnMasked,
            String codeAuthNumber,
            Instant createdAt,
            long ageMinutes,
            AgeBucket ageBucket,
            String why) {
    }

    public record ExceptionQueue(
            int totalCount,
            int paidUnconfirmedCount,
            int stuckCodeCount,
            int inDoubtCount,
            int over72hCount,
            List<ExceptionItem> items) {
    }

    @GetMapping("/exceptions")
    @SecurityRequirements({})
    @Operation(
            summary = "Operator workbasket — ledger rows that need a human",
            description = "Internal (X-Internal-Token). Three categories, oldest first: "
                    + "COMPLETED_UNCONFIRMED (customer PAID, booking not confirmed — after the retry loop "
                    + "gives up these are manual refunds; code payments have NO real-time reversal), "
                    + "TOKEN_ISSUED stuck past expiry (upstream status unreadable; the slot stays blocked "
                    + "by design — never auto-expired), and legacy IN_DOUBT rows. "
                    + "Age buckets: UNDER_24H work queue, H24_TO_72H overdue, OVER_72H escalation."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                    description = "The workbasket (possibly empty — the goal state)",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(name = "One paid-unconfirmed item", value = """
                                    {
                                      "code": "200 OK",
                                      "message": "3 exception(s) in the workbasket",
                                      "data": {
                                        "totalCount": 3,
                                        "paidUnconfirmedCount": 1,
                                        "stuckCodeCount": 1,
                                        "inDoubtCount": 1,
                                        "over72hCount": 0,
                                        "items": [
                                          {
                                            "paymentId": "f0e1d2c3-4567-890a-bcde-f01234567890",
                                            "paymentReference": "TKT-PMT-f0e1d2c3-4567-890a-bcde-f01234567890",
                                            "bookingId": "a3b9c1d2-1234-5678-9abc-def012345678",
                                            "status": "COMPLETED_UNCONFIRMED",
                                            "amount": 40.00,
                                            "currency": "USD",
                                            "msisdnMasked": "****6983",
                                            "codeAuthNumber": "1616800",
                                            "createdAt": "2026-06-12T07:01:00Z",
                                            "ageMinutes": 95,
                                            "ageBucket": "UNDER_24H",
                                            "why": "Customer PAID but the booking confirm keeps failing — manual ticket issue or refund"
                                          }
                                        ]
                                      }
                                    }
                                    """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
                    description = "Missing or invalid X-Internal-Token",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(name = "Unauthorized", value = """
                                    {
                                      "code": "401 UNAUTHORIZED",
                                      "message": "Missing or invalid X-Internal-Token",
                                      "data": null
                                    }
                                    """)))
    })
    public ResponseEntity<ApiResult<ExceptionQueue>> exceptions(
            @RequestHeader(value = INTERNAL_TOKEN_HEADER, required = false) String internalToken) {
        if (!authorizedInternal(internalToken)) {
            log.warn("Unauthorized GET /payments/internal/exceptions — missing or wrong X-Internal-Token");
            return unauthorized();
        }
        Instant now = Instant.now();
        List<ExceptionItem> items = new ArrayList<>();

        List<Payment> paidUnconfirmed = paymentRepository.findByStatus(
                PaymentStatus.COMPLETED_UNCONFIRMED, PageRequest.of(0, PAGE));
        for (Payment p : paidUnconfirmed) {
            items.add(item(p, now,
                    "Customer PAID but the booking confirm keeps failing — manual ticket issue or refund"));
        }

        List<Payment> stuckCodes = paymentRepository.findByStatusAndCodeExpiresAtBefore(
                PaymentStatus.TOKEN_ISSUED, now.minus(STUCK_CODE_SLACK), PageRequest.of(0, PAGE));
        for (Payment p : stuckCodes) {
            items.add(item(p, now,
                    "Code past expiry but upstream status unreadable — slot stays blocked until "
                            + "InnBucks answers (never auto-expired: the customer may have paid)"));
        }

        List<Payment> inDoubt = paymentRepository.findByStatus(
                PaymentStatus.IN_DOUBT, PageRequest.of(0, PAGE));
        for (Payment p : inDoubt) {
            items.add(item(p, now,
                    "Legacy IN_DOUBT row — resolve against InnBucks records manually"));
        }

        items.sort(Comparator.comparing(ExceptionItem::createdAt));
        int over72h = (int) items.stream().filter(i -> i.ageBucket() == AgeBucket.OVER_72H).count();
        ExceptionQueue queue = new ExceptionQueue(items.size(), paidUnconfirmed.size(),
                stuckCodes.size(), inDoubt.size(), over72h, items);
        return ResponseEntity.ok(ApiResult.ok(
                items.isEmpty() ? "Workbasket is empty" : items.size() + " exception(s) in the workbasket",
                queue));
    }

    // ------------------------------------------------------------ recon runs

    @GetMapping("/recon-runs")
    @SecurityRequirements({})
    @Operation(
            summary = "Settlement-reconciliation history (newest first)",
            description = "Internal (X-Internal-Token). The last 30 runs of the nightly match between our "
                    + "ledger and InnBucks' code mini-statement. A CLEAN run is the goal state; DISCREPANT "
                    + "carries the per-row detail; FAILED means the statement fetch broke and nothing was "
                    + "compared that night."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                    description = "Run history",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(name = "Clean night", value = """
                                    {
                                      "code": "200 OK",
                                      "message": "1 reconciliation run(s)",
                                      "data": [
                                        {
                                          "id": "9b2f0c4e-1111-2222-3333-444455556666",
                                          "windowStart": "2026-06-11T00:00:00Z",
                                          "windowEnd": "2026-06-12T00:00:00Z",
                                          "source": "MINI_STATEMENT",
                                          "status": "CLEAN",
                                          "coverageComplete": true,
                                          "matchedCount": 41,
                                          "matchedAmountCents": 164000,
                                          "oursNotTheirs": 0,
                                          "theirsNotOurs": 0,
                                          "amountMismatches": 0,
                                          "discrepancyDetail": null,
                                          "error": null,
                                          "createdAt": "2026-06-12T02:30:04Z"
                                        }
                                      ]
                                    }
                                    """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
                    description = "Missing or invalid X-Internal-Token",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(name = "Unauthorized", value = """
                                    {
                                      "code": "401 UNAUTHORIZED",
                                      "message": "Missing or invalid X-Internal-Token",
                                      "data": null
                                    }
                                    """)))
    })
    public ResponseEntity<ApiResult<List<ReconRun>>> reconRuns(
            @RequestHeader(value = INTERNAL_TOKEN_HEADER, required = false) String internalToken) {
        if (!authorizedInternal(internalToken)) {
            log.warn("Unauthorized GET /payments/internal/recon-runs — missing or wrong X-Internal-Token");
            return unauthorized();
        }
        List<ReconRun> runs = reconRunRepository.findTop30ByOrderByCreatedAtDesc();
        return ResponseEntity.ok(ApiResult.ok(runs.size() + " reconciliation run(s)", runs));
    }

    @PostMapping("/recon/run")
    @SecurityRequirements({})
    @Operation(
            summary = "Run settlement reconciliation now",
            description = "Internal (X-Internal-Token). Reconciles one UTC calendar day on demand — "
                    + "`date=YYYY-MM-DD`, defaulting to yesterday. Synchronous; returns the persisted run. "
                    + "422 when reconciliation is unconfigured (PAYMENTS_INNBUCKS_MERCHANT_ACCOUNT unset)."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                    description = "The persisted run (CLEAN / DISCREPANT / FAILED)",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(name = "Discrepant day", value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Reconciliation DISCREPANT for 2026-06-11",
                                      "data": {
                                        "id": "9b2f0c4e-1111-2222-3333-444455556666",
                                        "windowStart": "2026-06-11T00:00:00Z",
                                        "windowEnd": "2026-06-12T00:00:00Z",
                                        "source": "MINI_STATEMENT",
                                        "status": "DISCREPANT",
                                        "coverageComplete": true,
                                        "matchedCount": 40,
                                        "matchedAmountCents": 160000,
                                        "oursNotTheirs": 0,
                                        "theirsNotOurs": 1,
                                        "amountMismatches": 0,
                                        "discrepancyDetail": "THEIRS_NOT_OURS code=701999111 theirAmount=4000c NO MATCHING ROW",
                                        "error": null,
                                        "createdAt": "2026-06-12T08:15:11Z"
                                      }
                                    }
                                    """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
                    description = "Missing or invalid X-Internal-Token",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(name = "Unauthorized", value = """
                                    {
                                      "code": "401 UNAUTHORIZED",
                                      "message": "Missing or invalid X-Internal-Token",
                                      "data": null
                                    }
                                    """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "422",
                    description = "Reconciliation unconfigured",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(name = "No account", value = """
                                    {
                                      "code": "422 UNPROCESSABLE_CONTENT",
                                      "message": "Reconciliation is not configured — set PAYMENTS_INNBUCKS_MERCHANT_ACCOUNT",
                                      "data": null
                                    }
                                    """)))
    })
    public ResponseEntity<ApiResult<ReconRun>> runRecon(
            @RequestHeader(value = INTERNAL_TOKEN_HEADER, required = false) String internalToken,
            @RequestParam(value = "date", required = false) String date) {
        if (!authorizedInternal(internalToken)) {
            log.warn("Unauthorized POST /payments/internal/recon/run — missing or wrong X-Internal-Token");
            return unauthorized();
        }
        LocalDate day = date == null || date.isBlank()
                ? LocalDate.now(ZoneOffset.UTC).minusDays(1)
                : LocalDate.parse(date);
        log.info("POST /payments/internal/recon/run day={}", day);
        ReconRun run = settlementReconciliationJob.runFor(day);
        if (run == null) {
            return ResponseEntity.unprocessableEntity().body(
                    ApiResult.<ReconRun>builder()
                            .code("422 UNPROCESSABLE_CONTENT")
                            .message("Reconciliation is not configured — set PAYMENTS_INNBUCKS_MERCHANT_ACCOUNT")
                            .data(null)
                            .build());
        }
        return ResponseEntity.ok(ApiResult.ok(
                "Reconciliation " + run.getStatus() + " for " + day, run));
    }

    // ---------------------------------------------------------------- helpers

    private ExceptionItem item(Payment p, Instant now, String why) {
        long ageMinutes = Duration.between(p.getCreatedAt(), now).toMinutes();
        AgeBucket bucket = ageMinutes >= 72 * 60 ? AgeBucket.OVER_72H
                : ageMinutes >= 24 * 60 ? AgeBucket.H24_TO_72H
                : AgeBucket.UNDER_24H;
        return new ExceptionItem(p.getId(), p.getPaymentReference(), p.getBookingId(),
                p.getStatus().name(), p.getAmount(), p.getCurrency(),
                MsisdnMasking.mask(p.getCustomerMsisdn()), p.getCodeAuthNumber(),
                p.getCreatedAt(), ageMinutes, bucket, why);
    }

    /**
     * Constant-time token compare (mirrors EventController.authorizedInternal)
     * so an attacker cannot binary-search the secret from response timings.
     */
    private boolean authorizedInternal(String presented) {
        if (expectedInternalToken == null || expectedInternalToken.isBlank()) {
            log.warn("innbucks.internal-api-token is not configured; rejecting internal call");
            return false;
        }
        if (presented == null) {
            return false;
        }
        return MessageDigest.isEqual(
                expectedInternalToken.getBytes(StandardCharsets.UTF_8),
                presented.getBytes(StandardCharsets.UTF_8));
    }

    private static <T> ResponseEntity<ApiResult<T>> unauthorized() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(
                ApiResult.<T>builder()
                        .code("401 UNAUTHORIZED")
                        .message("Missing or invalid X-Internal-Token")
                        .data(null)
                        .build());
    }
}
