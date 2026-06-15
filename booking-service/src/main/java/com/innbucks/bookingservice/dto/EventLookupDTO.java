package com.innbucks.bookingservice.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

// Minimal mirror of event-service's EventResponseDTO. We capture `tenantId` at
// booking creation (to attribute loyalty transactions to the owning tenant) and
// `title` at confirmation time (for the WhatsApp e-ticket message's eventName) —
// the rest of the event payload is ignored.
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EventLookupDTO {

    private UUID eventId;
    private String tenantId;
    // Event display name, used as `eventName` on the WhatsApp e-ticket QR message.
    private String title;
}
