package com.innbucks.eventservice.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.innbucks.eventservice.config.FlexibleLocalDateTimeDeserializer;
import com.innbucks.eventservice.entity.EventCategory;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.AccessLevel;
import lombok.Data;
import lombok.Setter;
import java.time.LocalDateTime;

@Data
@Schema(
        name = "UpdateEventRequest",
        description = """
                Partial update payload. Omitted fields are left unchanged on the server.

                The event's `country` cannot be changed here — it is tied to the organizer's JWT.

                If `totalCapacity` is provided, `availableTickets` is adjusted by the difference in capacity.
                """
)
public class UpdateEventRequestDTO {

    @Schema(example = "Summer Concert (Updated)")
    @Size(max = 200, message = "Title must be at most 200 characters")
    private String title;

    @Schema(description = "Optional longer description.")
    @Size(max = 10000, message = "Description must be at most 10000 characters")
    private String description;

    @Schema(
            example = "PINKRUN26",
            description = """
                    Settlement code: 3-12 letters/digits (stored uppercase). Embedded in every InnBucks
                    payment reference for this event's bookings (`TKZ-<code>-<id>`) for per-event bank
                    settlement. Omit to leave unchanged.
                    """
    )
    @Pattern(regexp = "[A-Za-z0-9]{3,12}",
            message = "Settlement code must be 3-12 letters or digits")
    private String settlementCode;

    @Schema(example = "Harare Gardens")
    @Size(max = 255, message = "Venue must be at most 255 characters")
    private String venue;

    @Schema(
            example = "CONCERT",
            allowableValues = {"BOOKS", "COMEDY", "FUN_RUN", "HALF_MARATHON", "MARATHON", "CONCERT", "SPORT"},
            description = "Category of the event."
    )
    private EventCategory category;

    @Schema(description = "Geographic coordinates of the venue (latitude/longitude in decimal degrees).")
    @Valid
    private LocationDTO location;

    @Schema(
            example = "2026-06-15T19:00:00",
            description = """
                    Event start timestamp (`yyyy-MM-ddTHH:mm:ss`). If provided, must be strictly **in the future**.
                    Send the full ISO-8601 datetime; a date-only value (e.g. `"2026-06-15"`) is rejected.

                    **Send the local time of the market the event runs in** — the wall-clock an
                    attendee would read on a poster. The server converts it to UTC for storage
                    using the cell's market timezone (ZW = Africa/Harare, UTC+2), so `07:00` here
                    means a 7am start in Harare. Do NOT pre-convert to UTC and do not append an
                    offset. Responses come back as UTC with a `Z` suffix.
                    """
    )
    @Future(message = "Event start must be in the future")
    @JsonDeserialize(using = FlexibleLocalDateTimeDeserializer.class)
    private LocalDateTime startDateTime;

    @Schema(
            example = "2026-06-15T22:00:00",
            description = """
                    Event end timestamp (`yyyy-MM-ddTHH:mm:ss`). If provided, must be strictly **after** the start.

                    **Send the local time of the market the event runs in** — the wall-clock an
                    attendee would read on a poster. The server converts it to UTC for storage
                    using the cell's market timezone (ZW = Africa/Harare, UTC+2), so `07:00` here
                    means a 7am start in Harare. Do NOT pre-convert to UTC and do not append an
                    offset. Responses come back as UTC with a `Z` suffix.
                    """
    )
    @Future(message = "Event end must be in the future")
    @JsonDeserialize(using = FlexibleLocalDateTimeDeserializer.class)
    private LocalDateTime endDateTime;

    @Schema(description = "If set, must be >= 1.")
    @Min(value = 1, message = "Capacity must be at least 1")
    private Integer totalCapacity;

    @Schema(
            example = "2026-11-14T10:00:00",
            nullable = true,
            description = """
                    When ticket sales open, as the market's local time (like `startDateTime`). Must be before
                    the event's end.

                    - Left out: unchanged.
                    - A date-time: sales open then. A time at or before now opens them at once.
                    - `null`: removes the schedule, so tickets are on sale whenever the event is published.
                    """
    )
    @JsonDeserialize(using = FlexibleLocalDateTimeDeserializer.class)
    private LocalDateTime salesStartDateTime;

    // Set by the setter, so it tells "sent as null" (clear the schedule) apart
    // from "not sent" (leave it alone). Every other field here treats null as
    // "not sent", which is why this one needs its own flag.
    @JsonIgnore
    @Schema(hidden = true)
    @Setter(AccessLevel.NONE)
    private boolean salesStartDateTimeSent;

    @JsonSetter("salesStartDateTime")
    public void setSalesStartDateTime(LocalDateTime salesStartDateTime) {
        this.salesStartDateTime = salesStartDateTime;
        this.salesStartDateTimeSent = true;
    }

    @Schema(example = "1. Entry fees are not refundable.\n2. Runners must collect their race pack in person with ID.",
            nullable = true,
            description = """
                    Terms and conditions (V16). Leave the key out to keep them. Different text becomes the
                    next version, which buyers must accept again; the same text changes nothing. `null` or ""
                    removes the terms (past bookings keep the version they accepted). Plain text, HTML
                    stripped, at most 20,000 characters.
                    """)
    @Size(max = CreateEventRequestDTO.TERMS_MAX_LENGTH, message = CreateEventRequestDTO.TERMS_TOO_LONG_MESSAGE)
    private String termsText;

    // Same reason as salesStartDateTimeSent: null means "remove the terms",
    // which must not be confused with "not sent".
    @JsonIgnore
    @Schema(hidden = true)
    @Setter(AccessLevel.NONE)
    private boolean termsTextSent;

    @JsonSetter("termsText")
    public void setTermsText(String termsText) {
        this.termsText = termsText;
        this.termsTextSent = true;
    }

    // Only validates when both ends are supplied in the same request; the
    // service applies a further guard against the merged (stored) values when
    // only one side is updated.
    @AssertTrue(message = "endDateTime must be after startDateTime")
    @JsonIgnore
    @Schema(hidden = true)
    public boolean isEndAfterStart() {
        return startDateTime == null || endDateTime == null || endDateTime.isAfter(startDateTime);
    }
}
