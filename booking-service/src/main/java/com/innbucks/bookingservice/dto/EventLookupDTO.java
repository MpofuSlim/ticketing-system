package com.innbucks.bookingservice.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

// Minimal mirror of event-service's EventResponseDTO. We only need tenantId
// at booking creation so we can attribute loyalty transactions to the
// owning tenant — the rest of the event payload is ignored.
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EventLookupDTO {

    private UUID eventId;
    private String tenantId;
}
