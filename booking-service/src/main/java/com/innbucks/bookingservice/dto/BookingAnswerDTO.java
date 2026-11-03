package com.innbucks.bookingservice.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * One answer a ticket gave at checkout (V32). {@code label} and {@code type}
 * are the question's as they were when it was answered, so a later rename or
 * removal never changes them.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(name = "BookingAnswer")
public class BookingAnswerDTO {

    @Schema(example = "7b1e2c4a-0d3f-4e5a-9b6c-1a2b3c4d5e01")
    private UUID questionId;

    @Schema(example = "T-shirt size")
    private String label;

    @Schema(example = "CHOICE", allowableValues = {"TEXT", "NUMBER", "DATE", "CHOICE", "CHECKBOX"})
    private String type;

    @Schema(example = "M", description = "Always a string. CHECKBOX: \"true\"/\"false\"; DATE: yyyy-MM-dd.")
    private String value;
}
