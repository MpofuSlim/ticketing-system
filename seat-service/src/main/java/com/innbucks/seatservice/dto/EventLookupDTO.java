package com.innbucks.seatservice.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * Minimal mirror of event-service's EventResponseDTO. seat-service only needs
 * tenantId for category ownership checks; the rest of the event payload is
 * ignored — {@link JsonIgnoreProperties} keeps Jackson from blowing up if
 * event-service grows new fields.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class EventLookupDTO {

    private UUID eventId;
    private String tenantId;
}
