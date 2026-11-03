package com.innbucks.bookingservice.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.UUID;

/**
 * One of the organizer's booking questions, as event-service's sales window
 * hands it over (event-service V16): what each ticket's answer is checked
 * against at {@code POST /bookings}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BookingQuestionDTO {

    private UUID id;
    private String label;
    /** TEXT, NUMBER, DATE, CHOICE or CHECKBOX. */
    private String type;
    private Boolean required;
    private String helpText;
    private List<String> options;
    private Integer maxLength;
    /** Empty = every ticket is asked. */
    private List<UUID> categoryIds;
}
