package com.innbucks.bookingservice.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;

/**
 * Who made a box office sale, stamped on the booking at insert: the event's
 * organizer (V35), or a sales agent on their team (V36), with what
 * event-service said about the agent at that moment.
 *
 * @param userUuid          the selling account (the JWT {@code userUuid}); {@code sold_by_user_uuid}
 * @param name              what the reports call the seller ({@code sold_by_name}): the organizer's
 *                          sign-in email, or the name the organizer gave the agent
 * @param agentId           the agent's event-team row; null for the organizer
 * @param organizerUuid     the organizer whose team the agent is on; null for the organizer
 *                          (whose own sale is checked against the event before this is built)
 * @param ticketCap         the agent's ticket cap for the event; null = no cap
 * @param commissionPercent the agent's commission for the event, 0..100; null for the organizer
 */
public record BoxOfficeSeller(UUID userUuid, String name, UUID agentId, UUID organizerUuid,
                              Integer ticketCap, BigDecimal commissionPercent) {

    /** What a sales agent with no name on their team row is called in the reports. */
    static final String UNNAMED_AGENT = "Sales agent";

    /** The longest seller name the column holds. */
    private static final int MAX_NAME = 254;

    public static BoxOfficeSeller organizer(UUID userUuid, String name) {
        return new BoxOfficeSeller(userUuid, clip(name), null, null, null, null);
    }

    public static BoxOfficeSeller agent(UUID userUuid, String displayName, UUID agentId, UUID organizerUuid,
                                        Integer ticketCap, BigDecimal commissionPercent) {
        String name = displayName == null || displayName.isBlank() ? UNNAMED_AGENT : displayName.strip();
        return new BoxOfficeSeller(userUuid, clip(name), agentId, organizerUuid, ticketCap,
                commissionPercent == null ? BigDecimal.ZERO.setScale(2, RoundingMode.UNNECESSARY) : commissionPercent.setScale(2,
                        RoundingMode.HALF_UP));
    }

    public boolean isAgent() {
        return agentId != null;
    }

    /**
     * The agent's commission on a confirmed booking: {@code total x percent /
     * 100}, HALF_UP to the cent. Null when there is no total or no percentage.
     */
    public static BigDecimal commissionOn(BigDecimal total, BigDecimal percent) {
        if (total == null || percent == null) {
            return null;
        }
        return total.multiply(percent).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
    }

    private static String clip(String name) {
        if (name == null) {
            return null;
        }
        return name.length() <= MAX_NAME ? name : name.substring(0, MAX_NAME);
    }
}
