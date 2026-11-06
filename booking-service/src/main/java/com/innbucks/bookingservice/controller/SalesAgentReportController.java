package com.innbucks.bookingservice.controller;

import com.innbucks.bookingservice.dto.AgentHandoverRequestDTO;
import com.innbucks.bookingservice.dto.ApiResult;
import com.innbucks.bookingservice.dto.report.AgentHandoverDTO;
import com.innbucks.bookingservice.dto.report.ReportMetaDTO;
import com.innbucks.bookingservice.dto.report.SalesAgentsReportDTO;
import com.innbucks.bookingservice.exception.BadRequestException;
import com.innbucks.bookingservice.security.AuthenticatedCaller;
import com.innbucks.bookingservice.service.SalesAgentReportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * The organizer's view of their sales agents (V36): per-agent sales,
 * commission and cash, and the cash handovers the organizer records. Shares
 * the gateway's {@code /event-organizer/reports/**} route with the other
 * organizer reports. Reads answer the organizer for their own events and
 * platform staff for every organizer; writes are the organizer's alone.
 */
@RestController
@RequestMapping("/event-organizer/reports/sales-agents")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Organizer Reports", description = "Financial reports for an EVENT_ORGANIZER (scoped to their own "
        + "events) or platform staff (every organiser).")
@SecurityRequirement(name = "bearerAuth")
@PreAuthorize("hasAnyRole('EVENT_ORGANIZER','SUPER_ADMIN','PRODUCT_OFFICER','PRODUCT_MANAGER')")
public class SalesAgentReportController {

    static final String REPORT_EXAMPLE = """
            {
              "code": "200 OK",
              "message": "Sales agents retrieved",
              "data": {
                "agents": [
                  { "agentId": "9a8b7c6d-5e4f-4a3b-8c2d-1e0f9a8b7c6d", "displayName": "Chipo Banda", "ticketsSold": 4,
                    "cashTaken": 600.00, "cashVoided": 0.00, "onlineSales": 0.00, "commissionOwed": 0.00,
                    "handedOver": 600.00, "cashOwed": 0.00 },
                  { "agentId": "4b1e2c3d-5f60-4a7b-8c9d-0e1f2a3b4c5d", "displayName": "Tendai Moyo", "ticketsSold": 3,
                    "cashTaken": 450.00, "cashVoided": 150.00, "onlineSales": 150.00, "commissionOwed": 22.50,
                    "handedOver": 200.00, "cashOwed": 100.00 }
                ],
                "totals": { "ticketsSold": 7, "cashTaken": 1050.00, "cashVoided": 150.00, "onlineSales": 150.00,
                            "commissionOwed": 22.50, "handedOver": 800.00, "cashOwed": 100.00, "currency": "USD" }
              },
              "meta": { "from": null, "to": null, "eventId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                        "organizerUuid": "8b3a9c0e-9d12-4a3c-9c8a-2a1f0bda1d3e", "platformWide": false }
            }""";
    static final String HANDOVER_EXAMPLE = """
            {
              "code": "201 CREATED",
              "message": "Handover recorded",
              "data": { "id": "6d0f3c2b-8a41-4e7d-9b1c-2f5a7e9d0c11", "agentId": "4b1e2c3d-5f60-4a7b-8c9d-0e1f2a3b4c5d",
                        "eventId": "3fa85f64-5717-4562-b3fc-2c963f66afa6", "amount": 200.00, "currency": "USD",
                        "receivedAt": "2026-11-20T12:00:00+02:00", "recordedBy": "organizer@example.com",
                        "note": "Midday cash", "createdAt": "2026-11-20T12:01:30+02:00" }
            }""";
    static final String EXAMPLE_AGENT_NOT_FOUND = """
            { "code": "404 NOT_FOUND", "message": "This sales agent has made no sales for your events.", "data": { "errorCode": "AGENT_NOT_FOUND" } }""";
    static final String EXAMPLE_HANDOVER_EVENT_INVALID = """
            { "code": "400 BAD_REQUEST", "message": "This sales agent has made no sales for that event. Choose one of your events they sold for.", "data": { "errorCode": "HANDOVER_EVENT_INVALID" } }""";
    static final String EXAMPLE_HANDOVER_NOT_FOUND = """
            { "code": "404 NOT_FOUND", "message": "Handover not found", "data": { "errorCode": "HANDOVER_NOT_FOUND" } }""";
    private static final String FORBIDDEN = """
            { "code": "403 FORBIDDEN", "message": "Forbidden - insufficient role", "data": null }""";
    private static final String RANGE = """
            { "code": "400 BAD_REQUEST", "message": "'from' (2026-06-01) must not be after 'to' (2026-05-31).", "data": null }""";

    private final SalesAgentReportService service;

    @GetMapping
    @Operation(summary = "Sales agents",
            description = "Per sales agent, for your events: tickets sold, cash taken and voided, online sales, the "
                    + "commission you owe them (paid separately), the cash they handed over and the cash they still "
                    + "hold. One row per agent who sold, had a sale voided or handed cash over in the window, by "
                    + "name. A sale counts on the day it was made, a void and a handover on the day they happened. "
                    + "Cash taken counts CASH sales, voided later or not; cash voided takes a voided sale back out "
                    + "of what the agent owes; online sales are paid ONLINE sales (the money went online, not to the "
                    + "agent); commission is on CONFIRMED sales only (a voided or unpaid sale earns nothing). "
                    + "`cashOwed` = cashTaken - cashVoided - handedOver. Optional `eventId` (then only handovers "
                    + "recorded against that event count); `from`/`to` (ISO dates, UTC days) narrow the window, and "
                    + "with neither the report covers every sale, so `cashOwed` is everything still held. Platform "
                    + "staff see every organiser's agents.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Sales agents",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(
                            name = "Sales agents", value = REPORT_EXAMPLE))),
            @ApiResponse(responseCode = "400", description = "Invalid range",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(name = "Range",
                            value = RANGE))),
            @ApiResponse(responseCode = "403", description = "Not an organiser or platform staff",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(name = "Forbidden",
                            value = FORBIDDEN)))
    })
    public ResponseEntity<ApiResult<SalesAgentsReportDTO>> report(
            Authentication authentication,
            @Parameter(description = "Only this event") @RequestParam(required = false) UUID eventId,
            @Parameter(description = "First day, ISO date (UTC day)")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @Parameter(description = "Last day, ISO date (UTC day)")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        UUID organizerUuid = readScope(authentication);
        SalesAgentsReportDTO report = service.salesAgents(organizerUuid, eventId, from, to);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResult.ok("Sales agents retrieved",
                report, ReportMetaDTO.of(from, to, eventId, organizerUuid)));
    }

    @PostMapping("/{agentId}/handovers")
    @PreAuthorize("hasRole('EVENT_ORGANIZER')")
    @Operation(summary = "Record cash received from a sales agent",
            description = "Records cash you received from one of your sales agents; it reduces what they still "
                    + "owe in both reports. Only for an agent who has sold for one of your events, and, with "
                    + "`eventId`, for an event of yours they sold for. `receivedAt` is your local time (or an "
                    + "instant with Z or an offset), defaults to now, and can't be in the future. Handovers are "
                    + "never edited: delete a mistaken one and record it again.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Recorded",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(
                            name = "Recorded", value = HANDOVER_EXAMPLE))),
            @ApiResponse(responseCode = "400", description = "A bad amount, note or time, or an event the agent did "
                    + "not sell for",
                    content = @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "Amount", value = """
                                    { "code": "400 BAD_REQUEST", "message": "Validation failed", "data": { "amount": "The amount must be more than 0." } }"""),
                            @ExampleObject(name = "Event the agent did not sell for",
                                    value = EXAMPLE_HANDOVER_EVENT_INVALID),
                            @ExampleObject(name = "In the future", value = """
                                    { "code": "400 BAD_REQUEST", "message": "When the cash was received can't be in the future.", "data": null }""")
                    })),
            @ApiResponse(responseCode = "403", description = "Not an organiser",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(name = "Forbidden",
                            value = FORBIDDEN))),
            @ApiResponse(responseCode = "404", description = "The agent has made no sales for your events",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(
                            name = "Unknown agent", value = EXAMPLE_AGENT_NOT_FOUND)))
    })
    public ResponseEntity<ApiResult<AgentHandoverDTO>> recordHandover(
            Authentication authentication,
            @Parameter(description = "The sales agent (their event-team row)") @PathVariable UUID agentId,
            @Valid @RequestBody AgentHandoverRequestDTO request) {
        UUID organizerUuid = organizerOf(authentication);
        AgentHandoverDTO recorded = service.recordHandover(organizerUuid, agentId, request,
                AuthenticatedCaller.userUuid(authentication), authentication.getName());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResult.created("Handover recorded", recorded));
    }

    @GetMapping("/{agentId}/handovers")
    @Operation(summary = "Cash received from a sales agent",
            description = "Every handover recorded for the agent, newest first, deleted ones included (with "
                    + "`deletedAt` and `deletedBy`; they count in no total). Yours only; platform staff see every "
                    + "organiser's.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Handovers",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(
                            name = "Handovers", value = """
                                    {
                                      "code": "200 OK",
                                      "message": "Handovers retrieved",
                                      "data": [
                                        { "id": "8e1f2a3b-4c5d-4e6f-9a0b-1c2d3e4f5a6b", "agentId": "4b1e2c3d-5f60-4a7b-8c9d-0e1f2a3b4c5d",
                                          "eventId": null, "amount": 50.00, "currency": "USD",
                                          "receivedAt": "2026-11-20T17:30:00+02:00", "recordedBy": "organizer@example.com",
                                          "note": "Typed twice", "createdAt": "2026-11-20T17:31:00+02:00",
                                          "deletedAt": "2026-11-20T17:35:00+02:00", "deletedBy": "organizer@example.com" },
                                        { "id": "6d0f3c2b-8a41-4e7d-9b1c-2f5a7e9d0c11", "agentId": "4b1e2c3d-5f60-4a7b-8c9d-0e1f2a3b4c5d",
                                          "eventId": "3fa85f64-5717-4562-b3fc-2c963f66afa6", "amount": 200.00, "currency": "USD",
                                          "receivedAt": "2026-11-20T12:00:00+02:00", "recordedBy": "organizer@example.com",
                                          "note": "Midday cash", "createdAt": "2026-11-20T12:01:30+02:00" }
                                      ]
                                    }"""))),
            @ApiResponse(responseCode = "403", description = "Not an organiser or platform staff",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(name = "Forbidden",
                            value = FORBIDDEN)))
    })
    public ResponseEntity<ApiResult<List<AgentHandoverDTO>>> listHandovers(
            Authentication authentication,
            @Parameter(description = "The sales agent (their event-team row)") @PathVariable UUID agentId) {
        UUID organizerUuid = readScope(authentication);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResult.ok("Handovers retrieved", service.listHandovers(organizerUuid, agentId)));
    }

    @DeleteMapping("/{agentId}/handovers/{handoverId}")
    @PreAuthorize("hasRole('EVENT_ORGANIZER')")
    @Operation(summary = "Delete a recorded handover",
            description = "Takes a mistaken handover out of every total. It stays listed with who deleted it and "
                    + "when. Deleting one already deleted answers it unchanged, so a retry is safe.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Deleted",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(
                            name = "Deleted", value = """
                                    { "code": "200 OK", "message": "Handover deleted", "data": { "id": "8e1f2a3b-4c5d-4e6f-9a0b-1c2d3e4f5a6b", "agentId": "4b1e2c3d-5f60-4a7b-8c9d-0e1f2a3b4c5d", "eventId": null, "amount": 50.00, "currency": "USD", "receivedAt": "2026-11-20T17:30:00+02:00", "recordedBy": "organizer@example.com", "note": "Typed twice", "createdAt": "2026-11-20T17:31:00+02:00", "deletedAt": "2026-11-20T17:35:00+02:00", "deletedBy": "organizer@example.com" } }"""))),
            @ApiResponse(responseCode = "403", description = "Not an organiser",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(name = "Forbidden",
                            value = FORBIDDEN))),
            @ApiResponse(responseCode = "404", description = "Not one of your handovers for this agent",
                    content = @Content(mediaType = "application/json", examples = @ExampleObject(
                            name = "Not found", value = EXAMPLE_HANDOVER_NOT_FOUND)))
    })
    public ResponseEntity<ApiResult<AgentHandoverDTO>> deleteHandover(
            Authentication authentication,
            @Parameter(description = "The sales agent (their event-team row)") @PathVariable UUID agentId,
            @Parameter(description = "The handover") @PathVariable UUID handoverId) {
        UUID organizerUuid = organizerOf(authentication);
        return ResponseEntity.ok(ApiResult.ok("Handover deleted", service.deleteHandover(organizerUuid, agentId,
                handoverId, AuthenticatedCaller.userUuid(authentication), authentication.getName())));
    }

    /** Platform staff read every organizer's ({@code null}); an organizer reads their own. */
    @Nullable
    private static UUID readScope(Authentication authentication) {
        if (AuthenticatedCaller.isPlatformStaff(authentication)) {
            return null;
        }
        return organizerOf(authentication);
    }

    private static UUID organizerOf(Authentication authentication) {
        UUID organizerUuid = AuthenticatedCaller.organizerUuid(authentication);
        if (organizerUuid == null) {
            throw new BadRequestException(
                    "Your session is missing organizer identity. Please sign out and log in again.");
        }
        return organizerUuid;
    }
}
