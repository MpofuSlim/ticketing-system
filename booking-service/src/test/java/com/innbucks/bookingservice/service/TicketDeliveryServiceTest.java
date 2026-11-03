package com.innbucks.bookingservice.service;

import com.innbucks.bookingservice.client.EmailNotificationClient;
import com.innbucks.bookingservice.client.EventServiceClient;
import com.innbucks.bookingservice.client.NotificationDeliveryException;
import com.innbucks.bookingservice.client.WhatsAppNotificationClient;
import com.innbucks.bookingservice.entity.Booking;
import com.innbucks.bookingservice.entity.BookingItem;
import com.innbucks.bookingservice.util.SmsTextSanitizer;
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
        assertTrue(eventName.getValue().contains("TN-TENDAI"), eventName.getValue());
        assertFalse(eventName.getValue().contains("\n"), "WhatsApp template variable must be single-line");
        // The confirmation number opens every ticket of the booking at the
        // gate lookup: a guest gets their own ticket number and nothing more.
        assertFalse(eventName.getValue().contains(booking.getConfirmationNumber()), eventName.getValue());
        assertEquals("your event (ticket for Tendai Ncube, booked by Alice Moyo, ticket number TN-TENDAI)",
                eventName.getValue());

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
    void anAttendeesEmail_linksOnlyTheirOwnTicket_neverTheBookingsPage() {
        Fixture f = fixture();
        org.springframework.test.util.ReflectionTestUtils.setField(f.service(), "publicBaseUrl",
                "https://dtx.example/foundry");
        Booking booking = bookingWithOneTicket();
        booking.setCustomerName("Alice Moyo");
        booking.setUserEmail("alice@example.com");
        BookingItem rudo = ticketFor("TN-RUDO", "Rudo Sibanda", "rudo@example.com", "+263771111111");
        BookingItem tendai = ticketFor("TN-TENDAI", "Tendai Ncube", "tendai@example.com", "+263772222222");
        booking.setItems(List.of(rudo, tendai));

        f.service().deliver(booking);

        ArgumentCaptor<String> rudoSubject = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> rudoBody = ArgumentCaptor.forClass(String.class);
        verify(f.email()).sendEmail(eq("rudo@example.com"), rudoSubject.capture(), rudoBody.capture(),
                startsWith("ATT-"));
        String text = rudoBody.getValue();
        assertTrue(text.contains("View your ticket online: https://dtx.example/foundry/bookings/tickets/"
                + rudo.getId()), text);
        // Nothing in a guest's email opens the booking: not its page, not its
        // id, not its confirmation number (the gate lookup's key to every
        // ticket of the booking).
        assertFalse(text.contains(BOOKING_ID.toString()), text);
        assertFalse(text.contains("/tickets\n") || text.endsWith("/tickets"), text);
        assertFalse(text.contains("TN-TENDAI"), text);
        assertFalse(text.contains(tendai.getId().toString()), text);
        assertFalse(text.contains(booking.getConfirmationNumber()), text);
        assertEquals("Your InnBucks ticket for your event", rudoSubject.getValue());

        // The purchaser paid for the whole booking, so their receipt still
        // links it and names its reference.
        ArgumentCaptor<String> receiptSubject = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> receipt = ArgumentCaptor.forClass(String.class);
        verify(f.email()).sendEmail(eq("alice@example.com"), receiptSubject.capture(), receipt.capture(),
                startsWith("CONF-"));
        assertTrue(receipt.getValue().contains("https://dtx.example/foundry/bookings/" + BOOKING_ID + "/tickets"),
                receipt.getValue());
        assertTrue(receipt.getValue().contains("Booking reference: " + booking.getConfirmationNumber()),
                receipt.getValue());
        assertTrue(receiptSubject.getValue().contains(booking.getConfirmationNumber()), receiptSubject.getValue());
    }

    @Test
    void anAttendeesEmail_withNoPublicBaseUrl_carriesNoLink() {
        Fixture f = fixture();
        Booking booking = bookingWithOneTicket();
        booking.setItems(List.of(ticketFor("TN-RUDO", "Rudo Sibanda", "rudo@example.com", null)));

        f.service().deliver(booking);

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(f.email()).sendEmail(eq("rudo@example.com"), anyString(), body.capture(), startsWith("ATT-"));
        assertFalse(body.getValue().contains("online"), body.getValue());
        assertTrue(body.getValue().contains("TN-RUDO"), body.getValue());
    }

    @Test
    void attendeeWithEmailOnly_getsAOneTicketEmail_namingWhoBookedIt() {
        Fixture f = fixture();
        Booking booking = bookingWithOneTicket();
        booking.setCustomerName("Alice Moyo");
        booking.setItems(List.of(ticketFor("TN-RUDO", "Rudo Sibanda", "rudo@example.com", null)));

        TicketDeliveryService.Outcome outcome = f.service().deliver(booking);

        ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(f.email()).sendEmail(eq("rudo@example.com"), subject.capture(), body.capture(), startsWith("ATT-"));
        // The subject names the event, not the booking.
        assertEquals("Your InnBucks ticket for your event", subject.getValue());
        assertFalse(body.getValue().contains(booking.getConfirmationNumber()), body.getValue());
        assertFalse(body.getValue().contains("Booking reference"), body.getValue());
        assertTrue(body.getValue().contains("Hi Rudo Sibanda"), body.getValue());
        assertTrue(body.getValue().contains("Alice Moyo has booked a ticket for you"), body.getValue());
        assertTrue(body.getValue().contains("TN-RUDO"), body.getValue());
        // No phone of their own, and the buyer holds the QR — the email says
        // exactly that instead of falsely promising a WhatsApp delivery.
        assertTrue(body.getValue().contains("QR was sent to Alice Moyo's WhatsApp"), body.getValue());
        assertTrue(body.getValue().contains("present your ticket number at the gate"), body.getValue());
        assertEquals(1, outcome.attendeeDeliveriesSent());
        // Email-only attendee has no phone to receive the QR — the gate
        // credential stays on the PURCHASER's WhatsApp, so it always exists
        // somewhere scannable.
        verify(f.whatsApp()).sendEventQrCode(eq("+263782606983"), anyString(), contains("TN-RUDO"));
        assertEquals(1, outcome.qrTicketsTotal());
    }

    @Test
    void failedAttendeeQr_fallsBackToThePurchaser_soTheCredentialIsNeverLost() {
        // A typo'd or WhatsApp-less guest number would otherwise lose the gate
        // credential invisibly — and the resend button would re-fail the same
        // way forever. On a KNOWN send failure the QR re-routes to the buyer,
        // who forwards it. The other attendee is unaffected.
        Fixture f = fixture();
        Booking booking = bookingWithOneTicket();
        booking.setUserEmail("alice@example.com");
        booking.setItems(List.of(
                ticketFor("TN-BAD", "Bad Number", null, "+263779999999"),
                ticketFor("TN-OK", "Good Number", null, "+263778888888")));
        doThrow(new NotificationDeliveryException("gateway said no"))
                .when(f.whatsApp()).sendEventQrCode(eq("+263779999999"), anyString(), anyString());

        TicketDeliveryService.Outcome outcome = f.service().deliver(booking);

        verify(f.whatsApp()).sendEventQrCode(eq("+263778888888"), anyString(), contains("TN-OK"));
        // The failed ticket fell back to the purchaser's phone.
        verify(f.whatsApp()).sendEventQrCode(eq("+263782606983"), anyString(), contains("TN-BAD"));
        verify(f.whatsApp(), never()).sendEventQrCode(eq("+263782606983"), anyString(), contains("TN-OK"));
        assertEquals(1, outcome.qrTicketsSent());
        assertEquals(1, outcome.qrTicketsTotal());
        assertEquals(1, outcome.attendeeDeliveriesSent());
        assertEquals(2, outcome.attendeeDeliveriesTotal());
        // The receipt (sent AFTER the sends) reports the fallback honestly.
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(f.email()).sendEmail(eq("alice@example.com"), anyString(), body.capture(), anyString());
        assertTrue(body.getValue().contains("could not reach Bad Number's WhatsApp"), body.getValue());
        assertTrue(body.getValue().contains("please forward it"), body.getValue());
    }

    @Test
    void everyQrTheBuyerMayForward_namesOnlyItsOwnTicket() {
        // The buyer forwards these: a guest's QR that fell back to them, an
        // email-only guest's, and the spare tickets of a booking that named
        // nobody. A ticket number is the gate credential, so a forwarded
        // message must not carry anyone else's.
        Fixture f = fixture();
        Booking booking = bookingWithOneTicket();
        booking.setCustomerName("Alice Moyo");
        // TENDAI (delivered to their own phone) sits before BAD (fell back to
        // the buyer), so BAD is 3rd of the buyer's messages but 4th of the
        // booking: "ticket i of n" must count the booking, as the receipt does.
        // SELF is "I'm bringing myself": the buyer named themselves on a ticket
        // with their own number.
        booking.setItems(List.of(
                ticketFor("TN-ALICE", null, null, null),
                ticketFor("TN-RUDO", "Rudo Sibanda", "rudo@example.com", null),
                ticketFor("TN-TENDAI", "Tendai Ncube", null, "+263772000000"),
                ticketFor("TN-BAD", "Bad Number", null, "+263779999999"),
                ticketFor("TN-SELF", "Alice Moyo", null, "+263782606983")));
        doThrow(new NotificationDeliveryException("gateway said no"))
                .when(f.whatsApp()).sendEventQrCode(eq("+263779999999"), anyString(), anyString());

        f.service().deliver(booking);

        List<String> all = List.of("TN-ALICE", "TN-RUDO", "TN-BAD", "TN-TENDAI", "TN-SELF");
        for (String own : List.of("TN-ALICE", "TN-RUDO", "TN-BAD", "TN-SELF")) {
            ArgumentCaptor<String> caption = ArgumentCaptor.forClass(String.class);
            verify(f.whatsApp()).sendEventQrCode(eq("+263782606983"), caption.capture(), contains(own));
            assertTrue(caption.getValue().contains(own), caption.getValue());
            for (String other : all) {
                if (!other.equals(own)) {
                    assertFalse(caption.getValue().contains(other),
                            own + "'s message carries " + other + ": " + caption.getValue());
                }
            }
            assertFalse(caption.getValue().contains("\n"), "WhatsApp template variable must be single-line");
        }
        // A guest's QR (email-only RUDO, fallback BAD) carries no booking
        // reference: the buyer forwards it, and the reference opens every
        // ticket of the booking at the gate lookup.
        String reference = booking.getConfirmationNumber();
        ArgumentCaptor<String> rudo = ArgumentCaptor.forClass(String.class);
        verify(f.whatsApp()).sendEventQrCode(eq("+263782606983"), rudo.capture(), contains("TN-RUDO"));
        assertEquals("your event (ticket 2 of 5 — ticket number TN-RUDO (Rudo Sibanda))", rudo.getValue());
        ArgumentCaptor<String> bad = ArgumentCaptor.forClass(String.class);
        verify(f.whatsApp()).sendEventQrCode(eq("+263782606983"), bad.capture(), contains("TN-BAD"));
        assertEquals("your event (ticket 4 of 5 — ticket number TN-BAD (Bad Number))", bad.getValue());
        assertFalse(bad.getValue().contains(reference), bad.getValue());
        // The buyer's own ticket and the one they named themselves on keep it.
        ArgumentCaptor<String> alice = ArgumentCaptor.forClass(String.class);
        verify(f.whatsApp()).sendEventQrCode(eq("+263782606983"), alice.capture(), contains("TN-ALICE"));
        assertEquals("your event (booking " + reference + ", ticket 1 of 5 — ticket number TN-ALICE)", alice.getValue());
        ArgumentCaptor<String> self = ArgumentCaptor.forClass(String.class);
        verify(f.whatsApp()).sendEventQrCode(eq("+263782606983"), self.capture(), contains("TN-SELF"));
        assertEquals("your event (booking " + reference + ", ticket 5 of 5 — ticket number TN-SELF (Alice Moyo))",
                self.getValue());
    }

    @Test
    void aTicketNamingTheBuyerByEmail_isTheBuyers_andKeepsTheReference() {
        // "I'm bringing myself" by email alone: the attendee email is the
        // buyer's (in another case), so it is not a guest's ticket.
        Fixture f = fixture();
        Booking booking = bookingWithOneTicket();
        booking.setUserEmail("alice@example.com");
        booking.setTotalAmount(new java.math.BigDecimal("60.00"));
        booking.setItems(List.of(ticketFor("TN-SELF", "Alice Moyo", "ALICE@example.com", null)));

        f.service().deliver(booking);

        ArgumentCaptor<String> caption = ArgumentCaptor.forClass(String.class);
        verify(f.whatsApp()).sendEventQrCode(eq("+263782606983"), caption.capture(), contains("TN-SELF"));
        assertEquals("your event (booking " + booking.getConfirmationNumber()
                + ", 1 ticket, total 60.00 — ticket number TN-SELF (Alice Moyo))", caption.getValue());
    }

    @Test
    void failedAttendeeQr_theirEmailAdmitsIt_insteadOfClaimingWhatsAppDelivery() {
        // The QR send runs before the email is built, so the email must report
        // the outcome: a guest told "it's on your WhatsApp" when it isn't
        // discovers the truth at the gate.
        Fixture f = fixture();
        Booking booking = bookingWithOneTicket();
        booking.setCustomerName("Alice Moyo");
        booking.setItems(List.of(
                ticketFor("TN-BAD", "Bad Number", "bad@example.com", "+263779999999")));
        doThrow(new NotificationDeliveryException("gateway said no"))
                .when(f.whatsApp()).sendEventQrCode(eq("+263779999999"), anyString(), anyString());

        f.service().deliver(booking);

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(f.email()).sendEmail(eq("bad@example.com"), anyString(), body.capture(), startsWith("ATT-"));
        assertFalse(body.getValue().contains("has been sent to your WhatsApp"), body.getValue());
        assertTrue(body.getValue().contains("could not deliver the QR e-ticket to your WhatsApp"), body.getValue());
        assertTrue(body.getValue().contains("sent to Alice Moyo's"), body.getValue());
        // And the fallback QR really did reach the buyer.
        verify(f.whatsApp()).sendEventQrCode(eq("+263782606983"), anyString(), contains("TN-BAD"));
    }

    @Test
    void failedAttendeeQr_withNoPurchaserPhone_hasNowhereToGo_andNothingThrows() {
        Fixture f = fixture();
        Booking booking = bookingWithOneTicket();
        booking.setPhoneNumber(null);
        booking.setUserEmail("alice@example.com");
        booking.setItems(List.of(ticketFor("TN-BAD", "Bad Number", null, "+263779999999")));
        doThrow(new NotificationDeliveryException("gateway said no"))
                .when(f.whatsApp()).sendEventQrCode(anyString(), anyString(), anyString());

        TicketDeliveryService.Outcome outcome = f.service().deliver(booking);

        assertEquals(0, outcome.qrTicketsTotal());
        assertEquals(0, outcome.attendeeDeliveriesSent());
        assertTrue(outcome.emailSent(), "receipt email still goes out");
    }

    @Test
    void phoneOnlyBuyer_withEveryTicketRouted_getsAnSmsReceipt_notSilence() {
        // WhatsApp-first market: no email on the booking is normal. If every
        // QR went to a guest, the person who PAID would otherwise receive
        // zero messages.
        Fixture f = fixture();
        Booking booking = bookingWithOneTicket();
        booking.setUserEmail(null);
        booking.setCustomerName("Alice Moyo");
        booking.setItems(List.of(ticketFor("TN-TENDAI", "Tendai Ncube", null, "+263772000000")));

        f.service().deliver(booking);

        ArgumentCaptor<String> sms = ArgumentCaptor.forClass(String.class);
        verify(f.email()).sendSms(eq("+263782606983"), sms.capture(), anyString());
        assertTrue(sms.getValue().contains(booking.getConfirmationNumber()), sms.getValue());
        assertTrue(sms.getValue().contains("guests' tickets were sent"), sms.getValue());
        assertEquals("Booking INN-20260825-31D117 confirmed for your event."
                + " Your guests' tickets were sent to their WhatsApp numbers.", sms.getValue());
        assertEquals(sms.getValue(), SmsTextSanitizer.toGsmSafe(sms.getValue()),
                "the SMS must survive the gateway's sanitiser unchanged");
        verify(f.email(), never()).sendEmail(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void phoneOnlyBuyer_whoseWhatsAppHoldsOnlyGuestsTickets_getsAnSmsReceiptWithTheReference() {
        // A guest's QR caption carries no booking reference, so a buyer with no
        // email whose WhatsApp got only guests' QRs (to forward) would see the
        // reference nowhere. The SMS receipt carries it, and says truthfully
        // where each QR went.
        Fixture f = fixture();
        Booking booking = bookingWithOneTicket();
        booking.setUserEmail(null);
        booking.setCustomerName("Alice Moyo");
        booking.setItems(List.of(
                ticketFor("TN-RUDO", "Rudo Sibanda", null, null),
                ticketFor("TN-TENDAI", "Tendai Ncube", null, "+263772000000")));

        f.service().deliver(booking);

        verify(f.whatsApp()).sendEventQrCode(eq("+263782606983"), anyString(), contains("TN-RUDO"));
        ArgumentCaptor<String> sms = ArgumentCaptor.forClass(String.class);
        verify(f.email()).sendSms(eq("+263782606983"), sms.capture(), startsWith("CONF-"));
        assertEquals("Booking INN-20260825-31D117 confirmed for your event."
                + " 1 guest ticket was sent to your WhatsApp - please forward each one to its guest."
                + " The others went to their guests' own WhatsApp.", sms.getValue());
        assertEquals(sms.getValue(), SmsTextSanitizer.toGsmSafe(sms.getValue()),
                "the SMS must survive the gateway's sanitiser unchanged");
    }

    @Test
    void smsReceipt_neverClaimsAWhatsAppSendThatFailed() {
        // Every guest QR fell back to the buyer, and the buyer's WhatsApp
        // failed too: the SMS still carries the reference, and promises nothing.
        Fixture f = fixture();
        Booking booking = bookingWithOneTicket();
        booking.setUserEmail(null);
        booking.setItems(List.of(ticketFor("TN-BAD", "Bad Number", null, "+263779999999")));
        doThrow(new NotificationDeliveryException("gateway said no"))
                .when(f.whatsApp()).sendEventQrCode(anyString(), anyString(), anyString());

        f.service().deliver(booking);

        ArgumentCaptor<String> sms = ArgumentCaptor.forClass(String.class);
        verify(f.email()).sendSms(eq("+263782606983"), sms.capture(), anyString());
        assertEquals("Booking INN-20260825-31D117 confirmed for your event.", sms.getValue());
    }

    @Test
    void smsReceipt_isNotSent_whenTheBuyerAlreadyHearsSomething() {
        Fixture f = fixture();
        // Case 1: buyer has an email — the receipt email covers it.
        Booking withEmail = bookingWithOneTicket();
        withEmail.setUserEmail("alice@example.com");
        withEmail.setItems(List.of(ticketFor("TN-TENDAI", "Tendai Ncube", null, "+263772000000")));
        f.service().deliver(withEmail);
        verify(f.email(), never()).sendSms(anyString(), anyString(), anyString());

        // Case 2: no email but the buyer received their own QR.
        Fixture f2 = fixture();
        Booking ownTicket = bookingWithOneTicket();
        ownTicket.setUserEmail(null);
        f2.service().deliver(ownTicket);
        verify(f2.email(), never()).sendSms(anyString(), anyString(), anyString());

        // Case 3: no email, a guest's QR to forward AND an unnamed spare: the
        // spare's caption carries the reference.
        Fixture f3 = fixture();
        Booking mixed = bookingWithOneTicket();
        mixed.setUserEmail(null);
        mixed.setItems(List.of(
                ticketFor("TN-RUDO", "Rudo Sibanda", null, null),
                ticketFor("TN-SPARE", null, null, null)));
        f3.service().deliver(mixed);
        verify(f3.email(), never()).sendSms(anyString(), anyString(), anyString());

        // Case 4: no email, the buyer named themselves on their only ticket.
        Fixture f4 = fixture();
        Booking self = bookingWithOneTicket();
        self.setUserEmail(null);
        self.setItems(List.of(ticketFor("TN-SELF", "Alice Moyo", null, "+263782606983")));
        f4.service().deliver(self);
        verify(f4.email(), never()).sendSms(anyString(), anyString(), anyString());
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

        // The purchaser's QR message names ITS ticket only. It used to list
        // every guest's number as the buyer's receipt, but these messages get
        // forwarded and a ticket number is the gate credential. The full list
        // is the receipt email above (and the buyer's wallet).
        ArgumentCaptor<String> waName = ArgumentCaptor.forClass(String.class);
        verify(f.whatsApp(), atLeastOnce()).sendEventQrCode(eq("+263782606983"), waName.capture(), contains("TN-1"));
        assertTrue(waName.getValue().contains("ticket 1 of 2"), waName.getValue());
        assertTrue(waName.getValue().contains("TN-1"), waName.getValue());
        assertFalse(waName.getValue().contains("TN-2"), waName.getValue());
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
