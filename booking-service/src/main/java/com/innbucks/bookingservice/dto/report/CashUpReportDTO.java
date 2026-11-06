package com.innbucks.bookingservice.dto.report;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * The box office cash-up (V35): what each organiser account, and each sales
 * agent (V36), took and handed back in cash over the window, and every sale
 * and void behind it, so a till can be counted against it. A sale counts on
 * the day the cash was taken, a void on the day it was handed back. An
 * agent's till is named with the name the organiser gave the agent.
 */
@Schema(description = "Cash taken and handed back at the box office, by seller")
public record CashUpReportDTO(
        @Schema(description = "One row per account (or sales agent) that sold or voided in the window") List<Seller> sellers,
        Totals totals,
        @Schema(description = "Every sale and void in the window, oldest first") List<Entry> entries) {

    @Schema(description = "One account's till")
    public record Seller(
            @Schema(example = "organizer@example.com") String seller,
            @Schema(description = "Sales made", example = "12") int sales,
            @Schema(description = "Tickets in those sales", example = "27") int tickets,
            @Schema(description = "Cash taken", example = "3450.00") BigDecimal taken,
            @Schema(description = "Sales voided", example = "1") int voids,
            @Schema(description = "Cash handed back", example = "150.00") BigDecimal returned,
            @Schema(description = "taken - returned: what the till should hold", example = "3300.00") BigDecimal net,
            @Schema(description = "A sales agent's till (V36): the agent's event-team row, as on "
                    + "GET /event-organizer/reports/sales-agents. Absent on an organiser account's till.",
                    nullable = true, example = "4b1e2c3d-5f60-4a7b-8c9d-0e1f2a3b4c5d")
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
            UUID agentId) {
    }

    public record Totals(
            @Schema(example = "12") int sales,
            @Schema(example = "27") int tickets,
            @Schema(example = "3450.00") BigDecimal taken,
            @Schema(example = "1") int voids,
            @Schema(example = "150.00") BigDecimal returned,
            @Schema(example = "3300.00") BigDecimal net) {
    }

    @Schema(description = "A sale (cash taken) or a void (cash handed back)")
    public record Entry(
            @Schema(example = "SALE", allowableValues = {"SALE", "VOID"}) String type,
            @Schema(description = "When the cash was taken or handed back") LocalDateTime at,
            @Schema(description = "Who took or handed back the cash", example = "organizer@example.com") String by,
            UUID bookingId,
            @Schema(example = "INN-20261120-4F2A9C") String confirmationNumber,
            UUID eventId,
            @Schema(example = "Rufaro Moyo") String customerName,
            @Schema(example = "2") int tickets,
            @Schema(example = "300.00") BigDecimal amount,
            @Schema(description = "A void's reason; null on a sale", example = "Buyer changed their mind") String reason) {
    }
}
