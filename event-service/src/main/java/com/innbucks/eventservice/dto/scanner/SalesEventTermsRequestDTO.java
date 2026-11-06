package com.innbucks.eventservice.dto.scanner;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

/**
 * A sales agent's terms for one event (V21). Both optional: no cap, and 0%
 * commission. Sending the request again replaces both.
 */
@Schema(name = "SalesEventTermsRequest")
public record SalesEventTermsRequestDTO(
        @Schema(example = "200", nullable = true, minimum = "1",
                description = "Most tickets this agent may have sold for the event (voided and expired sales "
                        + "free theirs). Absent or null = no cap.")
        @Positive(message = SalesEventTermsRequestDTO.CAP_MESSAGE)
        Integer ticketCap,

        @Schema(example = "5.00", nullable = true, minimum = "0", maximum = "100", defaultValue = "0",
                description = "Percent of what the buyer paid (tickets and add-ons) on each confirmed sale, "
                        + "0 to 100, at most 2 decimals. Absent = 0. You pay it to the agent yourself.")
        @DecimalMin(value = "0", message = SalesEventTermsRequestDTO.COMMISSION_RANGE_MESSAGE)
        @DecimalMax(value = "100", message = SalesEventTermsRequestDTO.COMMISSION_RANGE_MESSAGE)
        @Digits(integer = 3, fraction = 2, message = SalesEventTermsRequestDTO.COMMISSION_DECIMALS_MESSAGE)
        BigDecimal commissionPercent
) {
    public static final String CAP_MESSAGE = "Ticket cap must be at least 1";
    public static final String COMMISSION_RANGE_MESSAGE = "Commission must be between 0 and 100";
    public static final String COMMISSION_DECIMALS_MESSAGE = "Commission can have at most 2 decimals";
}
