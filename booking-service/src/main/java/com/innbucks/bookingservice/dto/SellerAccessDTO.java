package com.innbucks.bookingservice.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * event-service's answer to "may this account sell tickets for this event?"
 * ({@code GET /events/internal/seller-access}), asked live before every sales
 * agent's box office sale. Allowed only for an accepted, switched-on team row
 * with Sell, assigned to the event, on the event's organizer's team.
 *
 * <p>When allowed: the team row ({@code agentId}), the organizer whose team it
 * is, the name the organizer typed (stored on the booking as its seller), the
 * agent's ticket cap for the event (null = no cap) and their commission
 * percentage (0..100). When refused every field but {@code allowed} is null.
 * event-service always answers 200, so a null answer here only ever means
 * "could not ask".
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SellerAccessDTO {
    private boolean allowed;
    private UUID agentId;
    private UUID organizerUuid;
    private String displayName;
    private Integer ticketCap;
    private BigDecimal commissionPercent;
}
