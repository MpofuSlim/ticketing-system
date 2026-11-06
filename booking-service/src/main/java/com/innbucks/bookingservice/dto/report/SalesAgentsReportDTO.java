package com.innbucks.bookingservice.dto.report;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * The organizer's sales agents (V36,
 * {@code GET /event-organizer/reports/sales-agents}): per agent, what they
 * sold, voided and sold online in the window, the commission the organizer
 * owes them, the cash they handed over and the cash they still hold.
 */
@Schema(name = "SalesAgentsReport", description = "Sales agents' sales, commission and cash")
public record SalesAgentsReportDTO(
        @Schema(description = "One row per agent who sold, had a sale voided or handed cash over in the window, "
                + "by name") List<Agent> agents,
        Totals totals) {

    @Schema(name = "SalesAgentRow")
    public record Agent(
            @Schema(description = "The agent's event-team row", example = "4b1e2c3d-5f60-4a7b-8c9d-0e1f2a3b4c5d")
            UUID agentId,
            @Schema(description = "The name the organiser gave the agent, as their latest sale stored it",
                    example = "Tendai Moyo")
            String displayName,
            @Schema(description = "Tickets in CONFIRMED sales made in the window", example = "6") int ticketsSold,
            @Schema(description = "CASH sales made in the window", example = "900.00") BigDecimal cashTaken,
            @Schema(description = "CASH sales voided in the window", example = "150.00") BigDecimal cashVoided,
            @Schema(description = "ONLINE sales paid, made in the window", example = "300.00")
            BigDecimal onlineSales,
            @Schema(description = "Commission on CONFIRMED sales made in the window: what you owe the agent, "
                    + "paid separately", example = "52.50")
            BigDecimal commissionOwed,
            @Schema(description = "Cash recorded as received from the agent in the window", example = "500.00")
            BigDecimal handedOver,
            @Schema(description = "cashTaken - cashVoided - handedOver: cash the agent still holds",
                    example = "250.00")
            BigDecimal cashOwed) {
    }

    @Schema(name = "SalesAgentsTotals")
    public record Totals(
            @Schema(example = "6") int ticketsSold,
            @Schema(example = "900.00") BigDecimal cashTaken,
            @Schema(example = "150.00") BigDecimal cashVoided,
            @Schema(example = "300.00") BigDecimal onlineSales,
            @Schema(example = "52.50") BigDecimal commissionOwed,
            @Schema(example = "500.00") BigDecimal handedOver,
            @Schema(example = "250.00") BigDecimal cashOwed,
            @Schema(example = "USD") String currency) {
    }
}
