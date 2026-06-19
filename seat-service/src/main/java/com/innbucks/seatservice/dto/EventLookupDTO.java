package com.innbucks.seatservice.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * Minimal mirror of event-service's EventResponseDTO. seat-service only needs
 * the owning organizer's stable user_uuid for ownership checks; the rest of
 * the event payload is ignored — {@link JsonIgnoreProperties} keeps Jackson
 * from blowing up if event-service grows new fields.
 *
 * The legacy email-based `tenantId` was removed in event-service V7
 * (PR #259) — the surviving owner pointer is `tenantUserUuid`, which
 * matches {@code users.user_uuid} in user-service and the
 * {@code organizerUuid} JWT claim.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class EventLookupDTO {

    private UUID eventId;
    private UUID tenantUserUuid;
}
