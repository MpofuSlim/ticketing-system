package com.innbucks.eventservice.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.innbucks.eventservice.config.FlexibleLocalDateTimeDeserializer;
import com.innbucks.eventservice.entity.EventCategory;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@Schema(name = "CreateEventRequest", description = "Payload for creating a new event. The event's country is not "
        + "supplied here — it is derived from the authenticated organizer's JWT `country` claim.")
public class CreateEventRequestDTO {

    @Schema(example = "Summer Concert")
    @NotBlank(message = "Title is required")
    @Size(max = 200, message = "Title must be at most 200 characters")
    private String title;

    @Schema(description = "Optional longer description.")
    @Size(max = 10000, message = "Description must be at most 10000 characters")
    private String description;

    @Schema(
            example = "PINKRUN26",
            description = """
                    Optional settlement code: 3-12 letters/digits (stored uppercase). Embedded in every
                    InnBucks payment reference for this event's bookings (`TKZ-<code>-<id>`), so the
                    merchant bank statement can be filtered/settled per event. Auto-derived from the
                    title when omitted.
                    """
    )
    @Pattern(regexp = "[A-Za-z0-9]{3,12}",
            message = "Settlement code must be 3-12 letters or digits")
    private String settlementCode;

    @Schema(example = "Harare Gardens")
    @NotBlank(message = "Venue is required")
    @Size(max = 255, message = "Venue must be at most 255 characters")
    private String venue;

    @Schema(
            example = "CONCERT",
            description = "Category of the event: a `code` from `GET /events/categories`."
    )
    @NotNull(message = "Category is required")
    private EventCategory category;

    @Schema(description = "Geographic coordinates of the venue (latitude/longitude in decimal degrees).")
    @NotNull(message = "Location is required")
    @Valid
    private LocationDTO location;

    @Schema(
            example = "2026-06-15T19:00:00",
            description = """
                    Event start timestamp (`yyyy-MM-ddTHH:mm:ss`). Must be strictly **in the future**.
                    Send the full ISO-8601 datetime including the time portion (e.g. `"2026-06-15T19:00:00"`);
                    a date-only value is rejected.

                    **Send the local time of the market the event runs in** — the wall-clock an
                    attendee would read on a poster. The server converts it to UTC for storage
                    using the cell's market timezone (ZW = Africa/Harare, UTC+2), so `07:00` here
                    means a 7am start in Harare. Do NOT pre-convert to UTC and do not append an
                    offset; the client sends what the organizer typed and nothing more.
                    Responses come back as UTC with a `Z` suffix.
                    """
    )
    @NotNull(message = "Start date and time is required")
    @Future(message = "Event start must be in the future")
    @JsonDeserialize(using = FlexibleLocalDateTimeDeserializer.class)
    private LocalDateTime startDateTime;

    @Schema(
            example = "2026-06-15T22:00:00",
            description = """
                    Event end timestamp (`yyyy-MM-ddTHH:mm:ss`). Must be strictly **after** `startDateTime`.
                    Send the full ISO-8601 datetime including the time portion.

                    **Send the local time of the market the event runs in** — the wall-clock an
                    attendee would read on a poster. The server converts it to UTC for storage
                    using the cell's market timezone (ZW = Africa/Harare, UTC+2), so `07:00` here
                    means a 7am start in Harare. Do NOT pre-convert to UTC and do not append an
                    offset; the client sends what the organizer typed and nothing more.
                    Responses come back as UTC with a `Z` suffix.
                    """
    )
    @NotNull(message = "End date and time is required")
    @Future(message = "Event end must be in the future")
    @JsonDeserialize(using = FlexibleLocalDateTimeDeserializer.class)
    private LocalDateTime endDateTime;

    @Schema(
            example = "2026-11-14T10:00:00",
            nullable = true,
            description = """
                    Optional: when ticket sales open (`yyyy-MM-ddTHH:mm:ss`). Leave it out to put tickets on
                    sale as soon as the event is published.

                    **Send the local time of the market**, exactly like `startDateTime`: no offset, no `Z`; the
                    server converts it. Must be **before `endDateTime`**. It may be before the event starts,
                    after it starts (door sales), or in the past (on sale as soon as published).

                    A published event before this time is still listed ("On sale from ..."), but bookings are
                    refused until it (`salesStatus: NOT_YET_ON_SALE`).
                    """
    )
    @JsonDeserialize(using = FlexibleLocalDateTimeDeserializer.class)
    private LocalDateTime salesStartDateTime;

    @Schema(
            example = "2026-12-05T18:00:00",
            nullable = true,
            description = """
                    Optional: when ticket sales close (`yyyy-MM-ddTHH:mm:ss`). Leave it out to sell until the
                    event ends.

                    **Send the local time of the market**, exactly like `startDateTime`: no offset, no `Z`; the
                    server converts it. Must be **after `salesStartDateTime`** (when one is sent) and **no
                    later than `endDateTime`**. It may be after the event starts (sales close an hour in).

                    From this time the event is still listed, but bookings are refused
                    (`salesStatus: SALES_CLOSED`), from every channel: the app, the website, the box office
                    and sales agents. A payment already started before it is honoured.
                    """
    )
    @JsonDeserialize(using = FlexibleLocalDateTimeDeserializer.class)
    private LocalDateTime salesEndDateTime;

    @Schema(example = "1. Entry fees are not refundable.\n2. Runners must collect their race pack in person with ID.",
            nullable = true,
            description = """
                    Optional terms and conditions a buyer must accept before booking (V16). Plain text: HTML
                    is stripped, line breaks are kept. At most 20,000 characters. Leave it out (or blank) for
                    no terms. Saving terms makes them version 1; each later change is the next version.
                    """)
    @Size(max = TERMS_MAX_LENGTH, message = TERMS_TOO_LONG_MESSAGE)
    private String termsText;

    public static final int TERMS_MAX_LENGTH = 20_000;
    public static final String TERMS_TOO_LONG_MESSAGE = "Terms and conditions must be at most 20,000 characters.";

    @Schema(description = "Maximum venue capacity.")
    @NotNull(message = "Total capacity is required")
    @Min(value = 1, message = "Capacity must be at least 1")
    private Integer totalCapacity;

    @AssertTrue(message = "endDateTime must be after startDateTime")
    @JsonIgnore
    @Schema(hidden = true)
    public boolean isEndAfterStart() {
        return startDateTime == null || endDateTime == null || endDateTime.isAfter(startDateTime);
    }
}
