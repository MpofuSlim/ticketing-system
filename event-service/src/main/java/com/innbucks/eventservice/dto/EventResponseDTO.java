package com.innbucks.eventservice.dto;

import com.innbucks.eventservice.entity.Province;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(name = "EventResponse", description = "Public event representation returned by this service.")
public class EventResponseDTO {

    @Schema(description = "Stable identifier for the event.")
    private UUID eventId;

    @Schema(example = "1", description = "1-based position of this event within the current /events/by-province response page. Not stored; null on other endpoints.")
    private Integer eventNo;

    @Schema(description = "Owning tenant identifier (typically the JWT subject/username).")
    private String tenantId;

    @Schema(example = "Summer Concert")
    private String title;

    @Schema(description = "Optional longer description.")
    private String description;

    @Schema(example = "Harare Gardens")
    private String venue;

    @Schema(example = "HRE", description = "Province code where the event is hosted.")
    private Province province;

    @Schema(
            description = """
                    Event date exposed as a calendar date (`yyyy-MM-dd`).

                    Note: Internally events store a full timestamp (`LocalDateTime`). This API returns only the date portion.
                    """
    )
    private LocalDate dateTime;

    @Schema(description = "Maximum venue capacity.")
    private Integer totalCapacity;

    @Schema(description = "Tickets still available for sale.")
    private Integer availableTickets;

    @Schema(description = "Creation timestamp (server time).")
    private LocalDateTime createdAt;

    @Schema(description = "Last update timestamp (server time).")
    private LocalDateTime updatedAt;

    @Schema(description = "Seat categories configured for this event, including sections and section prices.")
    private List<EventSeatCategoryResponseDTO> seatCategories;
}
