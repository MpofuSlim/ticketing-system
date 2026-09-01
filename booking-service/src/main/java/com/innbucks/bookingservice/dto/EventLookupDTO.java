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
    // Event end (UTC). `nullable = false` on the event-service side, so it is
    // present on every real event — but a Feign fallback yields a null DTO, and
    // a pre-existing row read through an older event-service build could omit
    // it, so TicketWindow still treats a null end defensively rather than
    // assuming it. Needed to tell a finished event from one still running:
    // startDateTime alone cannot distinguish PAST from LIVE.
    private LocalDateTime endDateTime;
    // Venue name, shown on the customer's ticket list. Display only — never
    // used for a decision.
    private String venue;
}
