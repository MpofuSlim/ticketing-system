package com.innbucks.eventservice.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * booking-service's question before every sale: may this event be sold right
 * now? Always answered 200, an unknown event included ({@link #NOT_FOUND}), so
 * the caller's Feign fallback can only mean "could not ask" and is refused as
 * such (503), never read as a verdict.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(name = "SalesWindow", description = "Whether an event can be sold right now (internal).")
public class SalesWindowDTO {

    /** {@link #status} of an event that does not exist or was deleted. */
    public static final String NOT_FOUND = "NOT_FOUND";

    private UUID eventId;

    @Schema(example = "ON_SALE", description = "ON_SALE, NOT_YET_ON_SALE, ENDED, NOT_PUBLISHED or NOT_FOUND.")
    private String status;

    @Schema(nullable = true, description = "When sales open (UTC on this S2S path). Null: no schedule.")
    private LocalDateTime salesStartDateTime;

    @Schema(nullable = true)
    private LocalDateTime endDateTime;

    @Schema(nullable = true, description = "The owning organizer, which booking-service stamps on the booking.")
    private UUID tenantUserUuid;
}
