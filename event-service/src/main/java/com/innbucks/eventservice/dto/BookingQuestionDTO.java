package com.innbucks.eventservice.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.UUID;

/**
 * A booking question, as the organizer sends it in
 * {@code PUT /events/{id}/booking-questions} and as every reader gets it back
 * (V16). {@code type} is a string on the way in, so an unknown type is a
 * field error naming the question rather than an unreadable body.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(name = "BookingQuestion", description = "A question asked for each ticket at checkout.")
public class BookingQuestionDTO {

    @Schema(example = "7b1e2c4a-0d3f-4e5a-9b6c-1a2b3c4d5e01", nullable = true,
            description = "Send it to keep a question (answers stay linked to it); leave it out for a new one.")
    private UUID id;

    @Schema(example = "T-shirt size", description = "At most 80 characters, unique on the event (any letter case).")
    private String label;

    @Schema(example = "CHOICE", allowableValues = {"TEXT", "NUMBER", "DATE", "CHOICE", "CHECKBOX"})
    private String type;

    @Schema(example = "true", description = "For CHECKBOX, true means it must be ticked. Default false.")
    private Boolean required;

    @Schema(example = "Unisex sizes", nullable = true, description = "At most 200 characters.")
    private String helpText;

    @Schema(example = "[\"S\", \"M\", \"L\", \"XL\"]",
            description = "CHOICE only: 2 to 20 different options of at most 60 characters. [] otherwise.")
    private List<String> options;

    @Schema(example = "120", nullable = true,
            description = "TEXT only: the longest answer, 1 to 500 (default 100). null otherwise.")
    private Integer maxLength;

    @Schema(example = "[]", description = "The ticket categories asked; [] = every ticket. At most 50.")
    private List<UUID> categoryIds;
}
