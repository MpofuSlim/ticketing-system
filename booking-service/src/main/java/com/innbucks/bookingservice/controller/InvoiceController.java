package com.innbucks.bookingservice.controller;

import com.innbucks.bookingservice.dto.ApiResult;
import com.innbucks.bookingservice.dto.invoice.GenerateInvoicesRequest;
import com.innbucks.bookingservice.dto.invoice.InvoiceResponse;
import com.innbucks.bookingservice.dto.invoice.InvoiceSummaryResponse;
import com.innbucks.bookingservice.dto.invoice.PageResponse;
import com.innbucks.bookingservice.entity.EventInvoice.InvoiceStatus;
import com.innbucks.bookingservice.exception.BadRequestException;
import com.innbucks.bookingservice.security.AuthenticatedCaller;
import com.innbucks.bookingservice.service.InvoiceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Platform commission invoices issued to event organizers.
 *
 * <p>Two audiences, one controller:
 * <ul>
 *   <li><b>SUPER_ADMIN</b> — sees every organizer's invoices, generates on
 *       demand, marks paid / cancels, and reads the dashboard summary.</li>
 *   <li><b>EVENT_ORGANIZER</b> — read-only, and only ever their own invoices
 *       (scoped by the {@code organizerUuid} JWT claim).</li>
 * </ul>
 * Invoices themselves are produced by the periodic scheduler; the
 * {@code POST /invoices/generate} endpoint is the admin manual/backfill path.
 */
@RestController
@RequestMapping("/invoices")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Invoices",
        description = "Platform→organizer commission invoices. Generated per billing cycle from CONFIRMED "
                + "ticket revenue; commission + VAT are snapshotted on each invoice.")
@SecurityRequirement(name = "bearerAuth")
public class InvoiceController {

    private static final int MAX_PAGE_SIZE = 100;

    private final InvoiceService invoiceService;

    @GetMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','EVENT_ORGANIZER')")
    @Operation(summary = "List invoices",
            description = "SUPER_ADMIN lists all organizers' invoices (optionally narrowed by `organizerUuid`); "
                    + "an EVENT_ORGANIZER lists only their own. Filter by `status`; newest issued first. "
                    + "Line items are omitted from the list — fetch one invoice for the per-event breakdown.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                    description = "A page of invoices",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(name = "Invoice page", value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Invoices retrieved",
                                      "data": {
                                        "content": [
                                          {
                                            "id": "b4c0d2e3-9f1a-4c5d-8e6f-1a2b3c4d5e6f",
                                            "invoiceNumber": "INV-2026-000042",
                                            "organizerUuid": "7c9e6679-7425-40de-944b-e07fc1f90ae7",
                                            "periodStart": "2026-05-01",
                                            "periodEnd": "2026-05-31",
                                            "status": "ISSUED",
                                            "currency": "USD",
                                            "confirmedBookings": 128,
                                            "ticketsSold": 342,
                                            "grossSales": 34200.00,
                                            "commissionRate": 10.0000,
                                            "commissionAmount": 3420.00,
                                            "taxRate": 15.0000,
                                            "taxAmount": 513.00,
                                            "totalAmount": 3933.00,
                                            "issuedAt": "2026-06-01T01:30:00",
                                            "dueAt": "2026-06-15T01:30:00",
                                            "paidAt": null,
                                            "cancelledAt": null,
                                            "lineItems": null
                                          }
                                        ],
                                        "page": 0, "size": 20, "totalElements": 1, "totalPages": 1
                                      }
                                    }
                                    """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
                    description = "Caller is neither SUPER_ADMIN nor EVENT_ORGANIZER",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    { "code": "403 FORBIDDEN", "message": "Forbidden - insufficient role", "data": null }
                                    """)))
    })
    public ResponseEntity<ApiResult<PageResponse<InvoiceResponse>>> list(
            Authentication authentication,
            @RequestParam(required = false) UUID organizerUuid,
            @RequestParam(required = false) InvoiceStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        UUID organizerScope = isSuperAdmin(authentication) ? null : requireOrganizer(authentication);
        Pageable pageable = PageRequest.of(Math.max(page, 0), clampSize(size),
                Sort.by(Sort.Direction.DESC, "issuedAt"));
        PageResponse<InvoiceResponse> data =
                invoiceService.list(organizerScope, organizerUuid, status, pageable);
        return ResponseEntity.ok(ApiResult.ok("Invoices retrieved", data));
    }

    @GetMapping("/summary")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Operation(summary = "Invoice dashboard summary",
            description = "Platform-wide counts and amounts by status, plus outstanding (ISSUED + OVERDUE) "
                    + "and settled (PAID) money. SUPER_ADMIN only.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                    description = "Summary computed",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Invoice summary retrieved",
                                      "data": {
                                        "totalInvoices": 210, "totalBilled": 82540.00,
                                        "issuedCount": 12, "overdueCount": 3, "paidCount": 190, "cancelledCount": 5,
                                        "outstandingAmount": 9320.00, "paidAmount": 72100.00, "currency": "USD"
                                      }
                                    }
                                    """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Not SUPER_ADMIN")
    })
    public ResponseEntity<ApiResult<InvoiceSummaryResponse>> summary() {
        return ResponseEntity.ok(ApiResult.ok("Invoice summary retrieved", invoiceService.summary()));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','EVENT_ORGANIZER')")
    @Operation(summary = "Get one invoice",
            description = "Full invoice with its per-event line items. An EVENT_ORGANIZER may only fetch their "
                    + "own — another organizer's invoice id returns 404 (existence is not leaked).")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                    description = "Invoice found",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(name = "Invoice", value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Invoice retrieved",
                                      "data": {
                                        "id": "b4c0d2e3-9f1a-4c5d-8e6f-1a2b3c4d5e6f",
                                        "invoiceNumber": "INV-2026-000042",
                                        "organizerUuid": "7c9e6679-7425-40de-944b-e07fc1f90ae7",
                                        "periodStart": "2026-05-01", "periodEnd": "2026-05-31",
                                        "status": "ISSUED", "currency": "USD",
                                        "confirmedBookings": 128, "ticketsSold": 342,
                                        "grossSales": 34200.00,
                                        "commissionRate": 10.0000, "commissionAmount": 3420.00,
                                        "taxRate": 15.0000, "taxAmount": 513.00, "totalAmount": 3933.00,
                                        "issuedAt": "2026-06-01T01:30:00", "dueAt": "2026-06-15T01:30:00",
                                        "paidAt": null, "cancelledAt": null,
                                        "lineItems": [
                                          { "eventId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                                            "confirmedBookings": 120, "ticketsSold": 320,
                                            "grossSales": 32000.00, "commissionAmount": 3200.00 },
                                          { "eventId": "9c1f0b2a-3d4e-5f60-7182-93a4b5c6d7e8",
                                            "confirmedBookings": 8, "ticketsSold": 22,
                                            "grossSales": 2200.00, "commissionAmount": 220.00 }
                                        ]
                                      }
                                    }
                                    """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
                    description = "No such invoice (or not owned by the calling organizer)",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    { "code": "404 NOT_FOUND", "message": "Invoice b4c0d2e3-9f1a-4c5d-8e6f-1a2b3c4d5e6f not found", "data": null }
                                    """)))
    })
    public ResponseEntity<ApiResult<InvoiceResponse>> getOne(Authentication authentication, @PathVariable UUID id) {
        UUID organizerScope = isSuperAdmin(authentication) ? null : requireOrganizer(authentication);
        return ResponseEntity.ok(ApiResult.ok("Invoice retrieved", invoiceService.getById(id, organizerScope)));
    }

    @PostMapping("/generate")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Operation(summary = "Generate invoices for a period",
            description = "Manually generate commission invoices for an explicit [periodStart, periodEnd] window "
                    + "(inclusive days). Omit `organizerUuid` to bill every organizer with revenue in the window, "
                    + "or set it to bill one. Idempotent — an organizer already invoiced for the exact period is "
                    + "skipped, as is any organizer whose billable commission is zero. SUPER_ADMIN only.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201",
                    description = "Generation ran; body lists the invoices actually created (may be empty)",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {
                                      "code": "201 CREATED",
                                      "message": "Generated 1 invoice(s)",
                                      "data": [
                                        { "id": "b4c0d2e3-9f1a-4c5d-8e6f-1a2b3c4d5e6f", "invoiceNumber": "INV-2026-000042",
                                          "organizerUuid": "7c9e6679-7425-40de-944b-e07fc1f90ae7",
                                          "periodStart": "2026-05-01", "periodEnd": "2026-05-31",
                                          "status": "ISSUED", "currency": "USD", "totalAmount": 3933.00 }
                                      ]
                                    }
                                    """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                    description = "Missing dates or periodStart after periodEnd",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    { "code": "400 BAD_REQUEST", "message": "periodStart (2026-06-01) must not be after periodEnd (2026-05-31).", "data": null }
                                    """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Not SUPER_ADMIN")
    })
    public ResponseEntity<ApiResult<List<InvoiceResponse>>> generate(
            @Valid @RequestBody GenerateInvoicesRequest request) {
        List<InvoiceResponse> generated = invoiceService.generateForPeriod(
                request.organizerUuid(), request.periodStart(), request.periodEnd());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResult.created("Generated " + generated.size() + " invoice(s)", generated));
    }

    @PostMapping("/{id}/mark-paid")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Operation(summary = "Mark an invoice paid",
            description = "Settle an ISSUED or OVERDUE invoice. Idempotent (re-marking a PAID invoice is a no-op). "
                    + "A CANCELLED invoice cannot be paid. SUPER_ADMIN only.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Marked paid",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    { "code": "200 OK", "message": "Invoice marked paid",
                                      "data": { "invoiceNumber": "INV-2026-000042", "status": "PAID", "paidAt": "2026-06-10T08:15:00" } }
                                    """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No such invoice"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
                    description = "Invoice is cancelled",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    { "code": "409 CONFLICT", "message": "Cannot mark a cancelled invoice as paid.", "data": null }
                                    """)))
    })
    public ResponseEntity<ApiResult<InvoiceResponse>> markPaid(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResult.ok("Invoice marked paid", invoiceService.markPaid(id)));
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Operation(summary = "Cancel an invoice",
            description = "Void an ISSUED or OVERDUE invoice. Idempotent. A PAID invoice cannot be cancelled "
                    + "(reverse the payment first). SUPER_ADMIN only.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Cancelled",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    { "code": "200 OK", "message": "Invoice cancelled",
                                      "data": { "invoiceNumber": "INV-2026-000042", "status": "CANCELLED", "cancelledAt": "2026-06-10T08:20:00" } }
                                    """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No such invoice"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
                    description = "Invoice is already paid",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    { "code": "409 CONFLICT", "message": "Cannot cancel a paid invoice.", "data": null }
                                    """)))
    })
    public ResponseEntity<ApiResult<InvoiceResponse>> cancel(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResult.ok("Invoice cancelled", invoiceService.cancel(id)));
    }

    private static boolean isSuperAdmin(Authentication authentication) {
        return authentication != null && authentication.getAuthorities().stream()
                .anyMatch(a -> "ROLE_SUPER_ADMIN".equals(a.getAuthority()));
    }

    /**
     * An organizer is scoped by their {@code organizerUuid} claim. Current
     * EVENT_ORGANIZER tokens carry it; a legacy token without it fails with a
     * clear 400 telling them to re-login rather than silently seeing nothing.
     */
    private UUID requireOrganizer(Authentication authentication) {
        UUID organizerUuid = AuthenticatedCaller.organizerUuid(authentication);
        if (organizerUuid == null) {
            throw new BadRequestException(
                    "Your session is missing organizer identity. Please sign out and log in again.");
        }
        return organizerUuid;
    }

    private static int clampSize(int size) {
        if (size < 1) {
            return 1;
        }
        return Math.min(size, MAX_PAGE_SIZE);
    }
}
