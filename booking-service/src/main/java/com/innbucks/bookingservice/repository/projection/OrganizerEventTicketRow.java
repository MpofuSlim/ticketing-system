package com.innbucks.bookingservice.repository.projection;

import java.util.UUID;

/**
 * One (organizer, event) ticket-count aggregate row. COUNT is taken over the
 * booking->items join (one row = one ticket); kept in a separate query from the
 * money aggregate so summing totalAmount never multiplies by ticket count.
 */
public interface OrganizerEventTicketRow {
    UUID getOrganizerUuid();

    UUID getEventId();

    long getTicketsSold();
}
