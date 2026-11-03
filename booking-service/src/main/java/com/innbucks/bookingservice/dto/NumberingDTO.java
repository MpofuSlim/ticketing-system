package com.innbucks.bookingservice.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/** A ticket category's number series and how far it has got (V34). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "A ticket category's number series")
public class NumberingDTO {

    @Schema(description = "The seat category", example = "5c2e7b1a-3d4f-4e6a-8b9c-0d1e2f3a4b5c")
    private UUID categoryId;

    @Schema(description = "The category's event", example = "3fa85f64-5717-4562-b3fc-2c963f66afa6")
    private UUID eventId;

    @Schema(example = "Bib")
    private String label;

    @Schema(example = "T")
    private String prefix;

    @Schema(example = "1")
    private int start;

    @Schema(example = "3")
    private int padding;

    @Schema(description = "How the first number reads", example = "T001")
    private String example;

    @Schema(description = "Numbers given so far, refunded tickets' included", example = "37")
    private int assigned;

    @Schema(description = "The number the next paid ticket gets", example = "T038")
    private String nextNumber;

    @Schema(description = "True once a number has been given: the series can no longer change or be removed",
            example = "true")
    private boolean locked;
}
