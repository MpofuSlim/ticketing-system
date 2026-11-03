package com.innbucks.bookingservice.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * event-service's answer to "may this event be sold right now?"
 * ({@code GET /events/internal/sales-window}), asked before every booking.
 * {@code status} is one of the constants below; event-service answers an
 * unknown event with {@link #NOT_FOUND} rather than an error, so a null answer
 * here only ever means "could not ask".
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EventSalesWindowDTO {

    public static final String ON_SALE = "ON_SALE";
    public static final String NOT_YET_ON_SALE = "NOT_YET_ON_SALE";
    public static final String ENDED = "ENDED";
    public static final String NOT_PUBLISHED = "NOT_PUBLISHED";
    public static final String NOT_FOUND = "NOT_FOUND";

    private UUID eventId;
    private String status;
    /** UTC, as event-service stores it. Null: no schedule. */
    private LocalDateTime salesStartDateTime;
    private LocalDateTime endDateTime;
    private UUID tenantUserUuid;
    /** The terms version a buyer must accept now (event-service V16); null = no terms. */
    private Integer termsVersion;
    /** The questions each ticket is checked against; null or empty = none. */
    private java.util.List<BookingQuestionDTO> bookingQuestions;
}
