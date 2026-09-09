package com.innbucks.eventservice.dto;

import com.innbucks.eventservice.entity.EventCategory;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * One selectable event category, as the FE picker consumes it: the wire
 * {@code code} (what create/update/filter accept and event responses carry)
 * plus the human {@code displayName}. Served by {@code GET /events/categories}
 * in curated display order so clients never hardcode the vocabulary.
 */
@Schema(name = "EventCategoryOption",
        description = "One selectable event category: the machine code to SEND and the label to SHOW.")
public record EventCategoryOptionDTO(
        @Schema(example = "GOSPEL_CONCERT",
                description = "Wire code — send this on POST/PUT /events and the ?category= filters; "
                        + "event responses carry it in `category`.")
        String code,
        @Schema(example = "Gospel Concert", description = "Human label for dropdowns. Never display the raw code.")
        String displayName) {

    public static EventCategoryOptionDTO of(EventCategory category) {
        return new EventCategoryOptionDTO(category.name(), category.getDisplayName());
    }
}
