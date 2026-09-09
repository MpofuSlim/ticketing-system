package com.innbucks.bookingservice.service;

import com.innbucks.bookingservice.client.EmailNotificationClient;
import com.innbucks.bookingservice.client.EventServiceClient;
import com.innbucks.bookingservice.client.NotificationDeliveryException;
import com.innbucks.bookingservice.client.WhatsAppNotificationClient;
import com.innbucks.bookingservice.entity.Booking;
import com.innbucks.bookingservice.entity.BookingItem;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.*;

/**
 * The QR e-ticket path is a wire contract with the WhatsApp gateway, not an
 * implementation detail — it is the one string that decides whether a paying
 * customer receives their ticket.
 *
 * <p>We send a path ONLY; the gateway rejects anything containing a domain. It
 * prepends its own configured BASE_URL, which ends in {@code /foundry/brand}
 * (it was provisioned for brand assets), and hands the result to Twilio as a
 * MediaUrl. So the path we send must be exactly
 * {@code /bookings/{id}/tickets/{tn}/qr.png} — no edge prefix — because the
 * gateway's base already carries one. {@code TicketController} serves the
 * {@code /brand/bookings/...} alias precisely so this resolves without any
 * change to the gateway's config.
 *
 * <p><b>Why this is pinned:</b> getting it wrong is invisible from here. The
 * gateway ACCEPTS our request and returns success, so we log a successful send;
 * Twilio then fails the media download with {@code 63019} minutes later, out of
 * our sight. Nothing in our logs or metrics moves, and a booking with no email
 * address has WhatsApp as its only channel, so the customer simply receives
 * nothing. Adding the {@code /foundry} prefix here once produced
 * {@code /foundry/brand/foundry/bookings/...} and did exactly that.
 */
class TicketDeliveryServiceTest {

    private static final UUID BOOKING_ID = UUID.fromString("8f6e4c06-e357-4980-852c-40de5fc97916");
    private static final String TICKET = "20260825-76931C";

    private record Fixture(TicketDeliveryService service,
                           WhatsAppNotificationClient whatsApp,
                           EmailNotificationClient email) {}

    private static Fixture fixture() {
        WhatsAppNotificationClient whatsApp = mock(WhatsAppNotificationClient.class);
        EmailNotificationClient email = mock(EmailNotificationClient.class);
        return new Fixture(
                new TicketDeliveryService(whatsApp, email, mock(EventServiceClient.class)),
                whatsApp, email);
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
    void qrPath_isBareAndUnprefixed_becauseTheGatewaysBaseUrlCarriesThePrefix() {
        Fixture f = fixture();

        f.service().deliver(bookingWithOneTicket());

        assertEquals("/bookings/" + BOOKING_ID + "/tickets/" + TICKET + "/qr.png",
                capturedQrPath(f),
                "The gateway prepends its own BASE_URL, which ends in /foundry/brand. "
                        + "Any prefix added here doubles it and Twilio fails the media "
                        + "download with 63019 — silently, since the gateway still returns "
                        + "success to us");
    }

    @Test
    void qrPath_carriesNoEdgePrefix_andNoDomain() {
        // Two separate failure modes, both fatal and both silent:
        //   - a /foundry prefix doubles the gateway's own base
        //   - an absolute URL is rejected outright by the gateway
        //     ("qrCodePath must be a path only — do not include the domain")
        Fixture f = fixture();

        f.service().deliver(bookingWithOneTicket());

        String path = capturedQrPath(f);
        assertTrue(path.startsWith("/bookings/"), "must start at /bookings/, was: " + path);
        assertFalse(path.startsWith("http"), "must not be absolute, was: " + path);
        assertFalse(path.contains("/foundry"), "must not carry the edge prefix, was: " + path);
        assertFalse(path.contains("/brand"), "the gateway's base supplies /brand, was: " + path);
        assertFalse(path.contains("//"), "no doubled slash, was: " + path);
    }

    @Test
    void qrPath_endsInPng_soTwilioSeesAnImageExtension() {
        Fixture f = fixture();

        f.service().deliver(bookingWithOneTicket());

        assertTrue(capturedQrPath(f).endsWith("/qr.png"));
    }

    @Test
    void oneSendPerTicket_eachWithItsOwnPath() {
        Fixture f = fixture();
        Booking booking = bookingWithOneTicket();
        booking.setItems(List.of(
                BookingItem.builder().id(UUID.randomUUID()).ticketNumber("TN-1").build(),
                BookingItem.builder().id(UUID.randomUUID()).ticketNumber("TN-2").build()));

        TicketDeliveryService.Outcome outcome = f.service().deliver(booking);

        ArgumentCaptor<String> paths = ArgumentCaptor.forClass(String.class);
        verify(f.whatsApp(), times(2)).sendEventQrCode(anyString(), anyString(), paths.capture());
        assertEquals(List.of(
                        "/bookings/" + BOOKING_ID + "/tickets/TN-1/qr.png",
                        "/bookings/" + BOOKING_ID + "/tickets/TN-2/qr.png"),
                paths.getAllValues());
        assertEquals(2, outcome.qrTicketsSent());
    }

    @Test
    void noPhone_meansNoQrSend_atAll() {
        Fixture f = fixture();
        Booking booking = bookingWithOneTicket();
        booking.setPhoneNumber(null);

        TicketDeliveryService.Outcome outcome = f.service().deliver(booking);

        verify(f.whatsApp(), never()).sendEventQrCode(anyString(), anyString(), anyString());
        assertFalse(outcome.anyChannelAttempted());
    }

    @Test
    void oneTicketsFailure_doesNotStopTheOthers() {
        Fixture f = fixture();
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

    // -- V22: named attendees get their own ticket --------------------------

    private static BookingItem ticketFor(String tn, String name, String email, String phone) {
        return BookingItem.builder()
                .id(UUID.randomUUID())
                .ticketNumber(tn)
                .categoryName("General")
                .attendeeName(name)
                .attendeeEmail(email)
                .attendeePhone(phone)
                .build();
    }

    @Test
    void eachHolderGetsExactlyTheirOwnTicket_neverSomeoneElses() {
        // The core routing rule: A gets A's QR, B gets B's — the purchaser
        // does NOT receive a copy of a ticket routed to its attendee's phone.
        Fixture f = fixture();
        Booking booking = bookingWithOneTicket();
        booking.setCustomerName("Alice Moyo");
        booking.setItems(List.of(
                ticketFor("TN-ALICE", null, null, null),
                ticketFor("TN-TENDAI", "Tendai Ncube", null, "+263772000000")));

        TicketDeliveryService.Outcome outcome = f.service().deliver(booking);

        // Purchaser: ONLY their own ticket.
        verify(f.whatsApp()).sendEventQrCode(eq("+263782606983"), anyString(), contains("TN-ALICE"));
        verify(f.whatsApp(), never()).sendEventQrCode(eq("+263782606983"), anyString(), contains("TN-TENDAI"));
        // Attendee: ONLY their ticket, to THEIR phone, in their name.
        ArgumentCaptor<String> eventName = ArgumentCaptor.forClass(String.class);
        verify(f.whatsApp()).sendEventQrCode(eq("+263772000000"), eventName.capture(), contains("TN-TENDAI"));
        verify(f.whatsApp(), never()).sendEventQrCode(eq("+263772000000"), anyString(), contains("TN-ALICE"));
        assertTrue(eventName.getValue().contains("ticket for Tendai Ncube"), eventName.getValue());
        assertTrue(eventName.getValue().contains("booked by Alice Moyo"), eventName.getValue());
        assertFalse(eventName.getValue().contains("\n"), "WhatsApp template variable must be single-line");

        assertEquals(1, outcome.qrTicketsSent(), "purchaser-routed QRs only");
        assertEquals(1, outcome.qrTicketsTotal());
        assertEquals(1, outcome.attendeeDeliveriesSent());
        assertEquals(1, outcome.attendeeDeliveriesTotal());
    }

    @Test
    void everyTicketNamed_purchaserGetsNoQr_butStillTheFullReceiptEmail() {
        Fixture f = fixture();
        Booking booking = bookingWithOneTicket();
        booking.setUserEmail("alice@example.com");
        booking.setCustomerName("Alice Moyo");
        booking.setItems(List.of(
                ticketFor("TN-TENDAI", "Tendai Ncube", null, "+263772000000"),
                ticketFor("TN-RUDO", "Rudo Sibanda", null, "+263773000000")));

        TicketDeliveryService.Outcome outcome = f.service().deliver(booking);

        // No QR to the purchaser's phone at all.
        verify(f.whatsApp(), never()).sendEventQrCode(eq("+263782606983"), anyString(), anyString());
        assertFalse(outcome.whatsappAttempted());
        assertEquals(0, outcome.qrTicketsTotal());
        // But the receipt email still covers the whole booking and says where
        // the QRs went.
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(f.email()).sendEmail(eq("alice@example.com"), anyString(), body.capture(), anyString());
        assertTrue(body.getValue().contains("TN-TENDAI"), body.getValue());
        assertTrue(body.getValue().contains("TN-RUDO"), body.getValue());
        assertTrue(body.getValue().contains("Every ticket has been sent to its attendee's WhatsApp"), body.getValue());
        assertEquals(2, outcome.attendeeDeliveriesSent());
    }

    @Test
    void attendeeWhoseContactIsThePurchasers_isNotDoubleSent() {
        // "I'm bringing myself" — the buyer names themselves on a ticket with
        // their own number. They already received every ticket.
        Fixture f = fixture();
        Booking booking = bookingWithOneTicket();
        booking.setUserEmail("alice@example.com");
        booking.setItems(List.of(
                ticketFor("TN-1", "Alice Moyo", "ALICE@example.com", "+263782606983")));

        TicketDeliveryService.Outcome outcome = f.service().deliver(booking);

        verify(f.whatsApp(), times(1)).sendEventQrCode(anyString(), anyString(), anyString());
        verify(f.email(), times(1)).sendEmail(anyString(), anyString(), anyString(), anyString());
        assertEquals(0, outcome.attendeeDeliveriesTotal());
    }

    @Test
    void attendeeWithEmailOnly_getsAOneTicketEmail_namingWhoBookedIt() {
        Fixture f = fixture();
        Booking booking = bookingWithOneTicket();
        booking.setCustomerName("Alice Moyo");
        booking.setItems(List.of(ticketFor("TN-RUDO", "Rudo Sibanda", "rudo@example.com", null)));

        TicketDeliveryService.Outcome outcome = f.service().deliver(booking);

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(f.email()).sendEmail(eq("rudo@example.com"), contains("ticket"), body.capture(), startsWith("ATT-"));
        assertTrue(body.getValue().contains("Hi Rudo Sibanda"), body.getValue());
        assertTrue(body.getValue().contains("Alice Moyo has booked a ticket for you"), body.getValue());
        assertTrue(body.getValue().contains("TN-RUDO"), body.getValue());
        assertTrue(body.getValue().contains("Present your ticket number at the gate"), body.getValue());
        assertEquals(1, outcome.attendeeDeliveriesSent());
        // Email-only attendee has no phone to receive the QR — the gate
        // credential stays on the PURCHASER's WhatsApp, so it always exists
        // somewhere scannable.
        verify(f.whatsApp()).sendEventQrCode(eq("+263782606983"), anyString(), contains("TN-RUDO"));
        assertEquals(1, outcome.qrTicketsTotal());
    }

    @Test
    void oneAttendeesFailure_doesNotStopAnotherAttendee() {
        Fixture f = fixture();
        Booking booking = bookingWithOneTicket();
        booking.setItems(List.of(
                ticketFor("TN-BAD", "Bad Number", null, "+263779999999"),
                ticketFor("TN-OK", "Good Number", null, "+263778888888")));
        doThrow(new NotificationDeliveryException("gateway said no"))
                .when(f.whatsApp()).sendEventQrCode(eq("+263779999999"), anyString(), anyString());

        TicketDeliveryService.Outcome outcome = f.service().deliver(booking);

        verify(f.whatsApp()).sendEventQrCode(eq("+263778888888"), anyString(), contains("TN-OK"));
        // Both tickets routed to their attendees, so none went to the
        // purchaser — a failed attendee send does NOT fall back to the buyer
        // (they can recover the QR from the booking page / wallet).
        verify(f.whatsApp(), never()).sendEventQrCode(eq("+263782606983"), anyString(), anyString());
        assertEquals(0, outcome.qrTicketsTotal());
        assertEquals(1, outcome.attendeeDeliveriesSent());
        assertEquals(2, outcome.attendeeDeliveriesTotal());
    }

    @Test
    void purchaserSummary_namesEachGuestAgainstTheirTicketNumber() {
        Fixture f = fixture();
        Booking booking = bookingWithOneTicket();
        booking.setUserEmail("alice@example.com");
        booking.setCustomerName("Alice Moyo");
        booking.setItems(List.of(
                ticketFor("TN-1", null, null, null),
                ticketFor("TN-2", "Tendai Ncube", null, "+263772000000")));

        f.service().deliver(booking);

        ArgumentCaptor<String> emailBody = ArgumentCaptor.forClass(String.class);
        verify(f.email()).sendEmail(eq("alice@example.com"), anyString(), emailBody.capture(), anyString());
        assertTrue(emailBody.getValue().startsWith("Hi Alice Moyo!"), emailBody.getValue());
        assertTrue(emailBody.getValue().contains("TN-2 (Tendai Ncube)"), emailBody.getValue());
        assertTrue(emailBody.getValue().contains("sent directly to their WhatsApp"), emailBody.getValue());

        // The purchaser's own QR send still carries the booking-level receipt
        // summary, guests' ticket numbers included — the receipt is theirs
        // even though Tendai's QR image is not.
        ArgumentCaptor<String> waName = ArgumentCaptor.forClass(String.class);
        verify(f.whatsApp(), atLeastOnce()).sendEventQrCode(eq("+263782606983"), waName.capture(), contains("TN-1"));
        assertTrue(waName.getValue().contains("TN-2 (Tendai Ncube)"), waName.getValue());
    }

    @Test
    void deliveryNeverThrows_soAConfirmedBookingIsNeverRolledBack() {
        // The booking is already CONFIRMED and the money has moved by the time
        // this runs; a notification failure must never propagate.
        Fixture f = fixture();
        doThrow(new RuntimeException("gateway down"))
                .when(f.whatsApp()).sendEventQrCode(anyString(), anyString(), anyString());
        doThrow(new RuntimeException("smtp down"))
                .when(f.email()).sendEmail(anyString(), anyString(), anyString(), anyString());
        Booking booking = bookingWithOneTicket();
        booking.setUserEmail("customer@example.com");

        assertDoesNotThrow(() -> f.service().deliver(booking));
    }
}
