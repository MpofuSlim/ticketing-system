package com.innbucks.seatservice.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * Minimal mirror of event-service's EventResponseDTO — the owning organizer's
 * stable user_uuid for ownership checks, plus the event's declared capacity for
 * the over-allocation guard. The rest of the payload is ignored;
 * {@link JsonIgnoreProperties} keeps Jackson from blowing up if event-service
 * grows new fields.
 *
 * The legacy email-based `tenantId` was removed in event-service V7
 * (PR #259) — the surviving owner pointer is `tenantUserUuid`, which
 * matches {@code users.user_uuid} in user-service and the
 * {@code organizerUuid} JWT claim.
 *
 * <p>{@code totalCapacity} needed no event-service change: the public
 * {@code GET /events/{id}} already returns it on {@code EventResponseDTO}, and
 * this DTO simply stopped discarding it. It is boxed rather than {@code int}
 * because a null (an event-service that somehow omits it) must read as
 * "unknown" and refuse, never as a capacity of zero.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class EventLookupDTO {

    private UUID eventId;
    private UUID tenantUserUuid;
    private Integer totalCapacity;
}
