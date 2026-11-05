package com.innbucks.bookingservice.service;

import com.innbucks.bookingservice.client.EventServiceClient;
import com.innbucks.bookingservice.dto.ApiResult;
import com.innbucks.bookingservice.dto.BookingResponseDTO;
import com.innbucks.bookingservice.dto.CreateBookingRequestDTO;
import com.innbucks.bookingservice.dto.EventLookupDTO;
import com.innbucks.bookingservice.dto.EventSalesWindowDTO;
import com.innbucks.bookingservice.exception.ApiProblemException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * The box office (V35): an event's organizer sells tickets for cash at their
 * outlet, from the Foundry portal.
 *
 * <p>A sale is an ordinary booking, created through the online path (so every
 * check an online sale gets — the sales window, terms and answers, add-ons,
 * promo codes, the per-booking ceiling, the stock lock — applies unchanged),
 * then CONFIRMED in the same request, paid in CASH and stamped with its seller.
 * The tickets, assigned numbers and messages are an online sale's exactly. It
 * never touches payment-service.
 *
 * <p>Owner decisions (2026-11-03): only the organizer's own account sells (its
 * login has a second factor; a gate scanner's session does not); the organizer may void
 * a sale, with a reason, until the event starts and while none of its tickets
 * is scanned; cash sales count in the commission like online ones; and the
 * box office sells in the same window as online.
 */
@Slf4j
@Service
public class BoxOfficeService {

    static final String TRY_AGAIN_MESSAGE = "We couldn't check this event just now. Please try again.";

    private final BookingService bookings;
    private final ObjectProvider<EventServiceClient> eventClientProvider;
    private final String eventInternalToken;

    public BoxOfficeService(BookingService bookings,
                            ObjectProvider<EventServiceClient> eventClientProvider,
                            @Value("${innbucks.internal-api-token:}") String eventInternalToken) {
        this.bookings = bookings;
        this.eventClientProvider = eventClientProvider;
        this.eventInternalToken = eventInternalToken;
    }

    /**
     * Sells: the caller must be the event's organizer (asked live; an unknown
     * or unpublished event is the online path's 404, an unanswered question a
     * 503, someone else's event a 403), then the booking is created and
     * confirmed. A failure between the two leaves an unpaid hold that expires
     * on its own: the seller is told the sale failed and takes no cash.
     */
    public BookingResponseDTO sell(CreateBookingRequestDTO request, String buyerEmail, String buyerPhone,
                                   UUID callerOrganizerUuid, UUID sellerUserUuid, String sellerName) {
        requireOrganizerOf(request.getEventId(), callerOrganizerUuid);
        BookingResponseDTO held = bookings.createBoxOfficeBooking(buyerEmail, buyerPhone, request);
        return bookings.confirmBoxOfficeSale(held.getId(), sellerUserUuid, sellerName);
    }

    /** Voids a cash sale; see {@link BookingService#voidCashSale}. */
    public BookingResponseDTO voidSale(UUID bookingId, String reason, UUID callerOrganizerUuid,
                                       UUID actorUserUuid, String actorName) {
        return bookings.voidCashSale(bookingId, callerOrganizerUuid, actorUserUuid, actorName, reason,
                this::eventStart);
    }

    private void requireOrganizerOf(UUID eventId, UUID callerOrganizerUuid) {
        if (callerOrganizerUuid == null) {
            throw new AccessDeniedException("Your session is missing organizer information. Please sign in again.");
        }
        EventSalesWindowDTO window = eventClient()
                .map(c -> c.salesWindow(eventId, eventInternalToken))
                .map(ApiResult::getData)
                .orElse(null);
        if (window == null || window.getStatus() == null) {
            throw new ApiProblemException(HttpStatus.SERVICE_UNAVAILABLE, "TRY_AGAIN", TRY_AGAIN_MESSAGE);
        }
        if (EventSalesWindowDTO.NOT_FOUND.equals(window.getStatus())
                || EventSalesWindowDTO.NOT_PUBLISHED.equals(window.getStatus())) {
            throw new ApiProblemException(HttpStatus.NOT_FOUND, "EVENT_NOT_FOUND", "Event not found");
        }
        if (!callerOrganizerUuid.equals(window.getTenantUserUuid())) {
            log.warn("Box office refused, not the event's organizer eventId={} caller={}", eventId, callerOrganizerUuid);
            throw new AccessDeniedException("You do not own this event");
        }
    }

    /** The event's start, UTC, asked live. Fails CLOSED: no answer is a 503, never "not started". */
    private LocalDateTime eventStart(UUID eventId) {
        EventLookupDTO event = eventClient()
                .map(c -> c.getEventInternal(eventId, eventInternalToken))
                .map(ApiResult::getData)
                .orElse(null);
        if (event == null || event.getStartDateTime() == null) {
            throw new ApiProblemException(HttpStatus.SERVICE_UNAVAILABLE, "TRY_AGAIN", TRY_AGAIN_MESSAGE);
        }
        return event.getStartDateTime();
    }

    private java.util.Optional<EventServiceClient> eventClient() {
        return java.util.Optional.ofNullable(eventClientProvider == null ? null : eventClientProvider.getIfAvailable());
    }
}
