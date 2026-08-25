package com.innbucks.bookingservice.service;

import com.innbucks.bookingservice.client.EmailNotificationClient;
import com.innbucks.bookingservice.client.EventServiceClient;
import com.innbucks.bookingservice.client.NotificationDeliveryException;
import com.innbucks.bookingservice.client.WhatsAppNotificationClient;
import com.innbucks.bookingservice.entity.Booking;
import com.innbucks.bookingservice.entity.BookingItem;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.*;

/**
 * The QR e-ticket URL is fetched from OUTSIDE the cluster, which makes its
 * exact shape a wire contract rather than an implementation detail.
 *
 * <p>We hand the WhatsApp gateway a domain-relative path; it prepends its own
 * base URL and Twilio then fetches that URL to attach the media. The public
 * edge mounts this system under a prefix ({@code /foundry}) and nginx strips it
 * before proxying, so a path built cluster-relative resolves, at the public
 * origin, to a 301 to HTML instead of a PNG. Twilio drops media it cannot
 * fetch, and the customer receives nothing — while our logs record a
 * successful send, because the gateway accepted the request and the fetch
 * fails afterwards, out of our sight.
 *
 * <p>That silence is why this is pinned by a test: the failure mode produces no
 * error anywhere in our system.
 */
class TicketDeliveryServiceTest {

    private static final UUID BOOKING_ID = UUID.fromString("8f6e4c06-e357-4980-852c-40de5fc97916");
    private static final String TICKET = "20260825-76931C";

    private record Fixture(TicketDeliveryService service,
                           WhatsAppNotificationClient whatsApp,
                           EmailNotificationClient email) {}

    private static Fixture fixture(String publicApiPrefix) {
        WhatsAppNotificationClient whatsApp = mock(WhatsAppNotificationClient.class);
        EmailNotificationClient email = mock(EmailNotificationClient.class);
        TicketDeliveryService service = new TicketDeliveryService(
                whatsApp, email, mock(EventServiceClient.class));
        ReflectionTestUtils.setField(service, "publicApiPrefix", publicApiPrefix);
        return new Fixture(service, whatsApp, email);
    }

    private static Booking bookingWithOneTicket() {
        return Booking.builder()
                .id(BOOKING_ID)
                .eventId(UUID.randomUUID())
                .confirmationNumber("INN-20260825-31D117")
                .phoneNumber("+263782606983")
                .items(List.of(BookingItem.builder()
                        .id(UUID.randomUUID())
                        .ticketNumber(TICKET)
                        .categoryName("General")
                        .build()))
                .build();
    }

    /** The path the gateway is actually handed, for one delivered booking. */
    private static String capturedQrPath(Fixture f) {
        ArgumentCaptor<String> path = ArgumentCaptor.forClass(String.class);
        verify(f.whatsApp()).sendEventQrCode(anyString(), anyString(), path.capture());
        return path.getValue();
    }

    @Test
    void qrPath_carriesThePublicEdgePrefix() {
        Fixture f = fixture("/foundry");

        f.service().deliver(bookingWithOneTicket());

        assertEquals("/foundry/bookings/" + BOOKING_ID + "/tickets/" + TICKET + "/qr.png",
                capturedQrPath(f),
                "The QR URL is resolved against the PUBLIC origin — without the edge "
                        + "prefix nginx answers the media fetch with a 301 to HTML and "
                        + "Twilio silently drops the ticket");
    }

    @Test
    void qrPath_isUnchanged_whenNoPrefixIsConfigured() {
        // Local dev / direct NodePort: we ARE the domain root, so today's
        // behaviour must be preserved exactly.
        Fixture f = fixture("");

        f.service().deliver(bookingWithOneTicket());

        assertEquals("/bookings/" + BOOKING_ID + "/tickets/" + TICKET + "/qr.png",
                capturedQrPath(f));
    }

    @Test
    void qrPath_normalisesAwkwardPrefixes() {
        // A prefix set as "foundry/" or "/foundry/" must land identically to
        // "/foundry". A doubled or missing slash is not cosmetic here — it is
        // another 301 at the edge, i.e. another silently undelivered ticket.
        for (String awkward : List.of("foundry", "/foundry/", "  /foundry  ", "/foundry//")) {
            Fixture f = fixture(awkward);

            f.service().deliver(bookingWithOneTicket());

            assertEquals("/foundry/bookings/" + BOOKING_ID + "/tickets/" + TICKET + "/qr.png",
                    capturedQrPath(f),
                    "prefix '" + awkward + "' should normalise to /foundry");
        }
    }

    @Test
    void qrPath_staysDomainRelative_whichTheClientRequires() {
        // WhatsAppNotificationClient rejects anything not starting with '/'
        // (the gateway prepends its own base URL). Prefixing must not turn the
        // path into an absolute URL.
        Fixture f = fixture("/foundry");

        f.service().deliver(bookingWithOneTicket());

        String path = capturedQrPath(f);
        assertTrue(path.startsWith("/"), "must stay domain-relative, was: " + path);
        assertFalse(path.contains("//"), "no doubled slash, was: " + path);
        assertFalse(path.startsWith("http"), "must not become absolute, was: " + path);
    }

    @Test
    void oneSendPerTicket_andPrefixAppliedToEach() {
        Fixture f = fixture("/foundry");
        Booking booking = bookingWithOneTicket();
        booking.setItems(List.of(
                BookingItem.builder().id(UUID.randomUUID()).ticketNumber("TN-1").build(),
                BookingItem.builder().id(UUID.randomUUID()).ticketNumber("TN-2").build()));

        TicketDeliveryService.Outcome outcome = f.service().deliver(booking);

        ArgumentCaptor<String> paths = ArgumentCaptor.forClass(String.class);
        verify(f.whatsApp(), times(2)).sendEventQrCode(anyString(), anyString(), paths.capture());
        assertEquals(List.of(
                        "/foundry/bookings/" + BOOKING_ID + "/tickets/TN-1/qr.png",
                        "/foundry/bookings/" + BOOKING_ID + "/tickets/TN-2/qr.png"),
                paths.getAllValues());
        assertEquals(2, outcome.qrTicketsSent());
    }

    @Test
    void noPhone_meansNoQrSend_atAll() {
        Fixture f = fixture("/foundry");
        Booking booking = bookingWithOneTicket();
        booking.setPhoneNumber(null);

        TicketDeliveryService.Outcome outcome = f.service().deliver(booking);

        verify(f.whatsApp(), never()).sendEventQrCode(anyString(), anyString(), anyString());
        assertFalse(outcome.anyChannelAttempted());
    }

    @Test
    void oneTicketsFailure_doesNotStopTheOthers() {
        Fixture f = fixture("/foundry");
        Booking booking = bookingWithOneTicket();
        booking.setItems(List.of(
                BookingItem.builder().id(UUID.randomUUID()).ticketNumber("TN-BAD").build(),
                BookingItem.builder().id(UUID.randomUUID()).ticketNumber("TN-OK").build()));
        doThrow(new NotificationDeliveryException("gateway said no"))
                .when(f.whatsApp()).sendEventQrCode(anyString(), anyString(), contains("TN-BAD"));

        TicketDeliveryService.Outcome outcome = f.service().deliver(booking);

        verify(f.whatsApp()).sendEventQrCode(anyString(), anyString(), contains("TN-OK"));
        assertEquals(1, outcome.qrTicketsSent());
        assertEquals(2, outcome.qrTicketsTotal());
    }

    @Test
    void deliveryNeverThrows_soAConfirmedBookingIsNeverRolledBack() {
        // The booking is already CONFIRMED and the money has moved by the time
        // this runs; a notification failure must never propagate.
        Fixture f = fixture("/foundry");
        doThrow(new RuntimeException("gateway down"))
                .when(f.whatsApp()).sendEventQrCode(anyString(), anyString(), anyString());
        doThrow(new RuntimeException("smtp down"))
                .when(f.email()).sendEmail(anyString(), anyString(), anyString(), anyString());
        Booking booking = bookingWithOneTicket();
        booking.setUserEmail("customer@example.com");

        assertDoesNotThrow(() -> f.service().deliver(booking));
    }


}
