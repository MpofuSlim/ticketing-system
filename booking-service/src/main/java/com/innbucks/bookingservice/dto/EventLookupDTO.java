package com.innbucks.bookingservice.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

// Minimal mirror of event-service's EventResponseDTO. We capture `tenantUserUuid`
// at booking creation (the owning organizer's stable cross-service id, mirrored
// onto bookings.tenant_user_uuid for loyalty attribution and ticket-scan
// authorization) and `title` at confirmation time (for the WhatsApp e-ticket
// message's eventName) — the rest of the event payload is ignored.
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EventLookupDTO {

    private UUID eventId;
    // Stable cross-service organizer identifier. Mirrored onto
    // bookings.tenant_user_uuid so the ticket-scan handler can authorize
    // "scanner's organizerUuid == booking's tenantUserUuid" without a
    // per-scan cross-service call, and used as the loyalty attribution key.
    private UUID tenantUserUuid;
    // Event display name, used as `eventName` on the WhatsApp e-ticket QR message.
    private String title;
    // Event start, used by EventReminderScheduler to find events starting within
    // the reminder window. LocalDateTime in UTC, same as event-service stores it.
    private LocalDateTime startDateTime;
}
