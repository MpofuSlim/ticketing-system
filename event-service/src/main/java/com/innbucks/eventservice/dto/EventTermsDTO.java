package com.innbucks.eventservice.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/** One version of an event's terms and conditions: what a buyer of that version accepted (V16). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(name = "EventTerms")
public class EventTermsDTO {

    private UUID eventId;

    @Schema(example = "1")
    private int version;

    @Schema(example = "1. Entry fees are not refundable.\n2. Runners must collect their race pack in person.",
            description = "Plain text with line breaks.")
    private String text;

    @Schema(description = "When this version was saved.")
    private LocalDateTime createdAt;
}
