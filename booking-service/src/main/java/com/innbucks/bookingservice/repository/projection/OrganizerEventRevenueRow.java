package com.innbucks.bookingservice.repository.projection;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One (organizer, event) confirmed-revenue aggregate row. Money is SUMmed per
 * booking (no item join) so the total can't fan out by ticket count.
 */
public interface OrganizerEventRevenueRow {
    UUID getOrganizerUuid();

    UUID getEventId();

    long getConfirmedBookings();

    BigDecimal getGrossSales();
}
