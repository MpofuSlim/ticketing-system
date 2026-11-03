package com.innbucks.bookingservice.service;

import com.innbucks.bookingservice.entity.Booking;
import com.innbucks.bookingservice.entity.BookingItem;
import com.innbucks.bookingservice.repository.BookingItemRepository;
import com.innbucks.bookingservice.repository.BookingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pins the bearer-instrument rules of the ticket renderer: only CONFIRMED
 * bookings render, the QR is real PNG bytes, a ticket number must belong to
 * the booking, and the absolute hosted-QR URL is woven into both the page and
 * the email so they resolve from outside any app session.
 */
class TicketRenderingServiceTest {

    private static final String BASE = "https://api.test";
    private static final String BOOKING_TOKEN = "0123456789abcdef0123456789abcdef";
    private static final String ITEM_TOKEN = "fedcba9876543210fedcba9876543210";
    private static final String GUEST_TOKEN = "aaaabbbbccccddddeeeeffff00001111";

    private BookingRepository repo;
    private BookingItemRepository itemRepo;
    private TicketRenderingService service;

    @BeforeEach
    void setUp() {
        repo = mock(BookingRepository.class);
        itemRepo = mock(BookingItemRepository.class);
        service = new TicketRenderingService(repo, new QrCodeGenerator(), itemRepo);
    }

    private static Booking booking(Booking.BookingStatus status) {
        Booking b = new Booking();
        b.setId(UUID.randomUUID());
        b.setStatus(status);
        b.setConfirmationNumber("INN-20260610-A1B2C3");
        b.setTotalAmount(new BigDecimal("50.00"));
        b.setLinkToken(BOOKING_TOKEN);
        b.setCreatedAt(LocalDateTime.of(2026, 6, 1, 10, 0));
        BookingItem item = new BookingItem();
        item.setId(UUID.randomUUID());
        item.setLinkToken(ITEM_TOKEN);
        item.setTicketNumber("20260610-T1");
        item.setCategoryName("VIP");
        item.setRowLabel("A");
        item.setSeatNumber(12);
        b.setItems(List.of(item));
        return b;
    }

    @Test
    void qr_confirmedBooking_returnsRealPngBytes() {
        Booking b = booking(Booking.BookingStatus.CONFIRMED);
        when(repo.findByIdWithItems(b.getId())).thenReturn(Optional.of(b));

        Optional<byte[]> png = service.ticketQrPng(b.getId(), "20260610-T1");

        assertThat(png).isPresent();
        byte[] bytes = png.get();
        // PNG magic number: 89 50 4E 47.
        assertThat(bytes.length).isGreaterThan(8);
        assertThat(bytes[0] & 0xFF).isEqualTo(0x89);
        assertThat(bytes[1]).isEqualTo((byte) 'P');
        assertThat(bytes[2]).isEqualTo((byte) 'N');
        assertThat(bytes[3]).isEqualTo((byte) 'G');
    }

    @Test
    void qr_ticketNotOnBooking_isEmpty() {
        Booking b = booking(Booking.BookingStatus.CONFIRMED);
        when(repo.findByIdWithItems(b.getId())).thenReturn(Optional.of(b));

        assertThat(service.ticketQrPng(b.getId(), "SOMEONE-ELSES-TICKET")).isEmpty();
    }

    @Test
    void qr_pendingBooking_isEmpty_neverRendersUnpaidTicket() {
        Booking b = booking(Booking.BookingStatus.PENDING);
        when(repo.findByIdWithItems(b.getId())).thenReturn(Optional.of(b));

        assertThat(service.ticketQrPng(b.getId(), "20260610-T1")).isEmpty();
    }

    @Test
    void qr_unknownBooking_isEmpty() {
        UUID id = UUID.randomUUID();
        when(repo.findByIdWithItems(id)).thenReturn(Optional.empty());

        assertThat(service.ticketQrPng(id, "20260610-T1")).isEmpty();
    }

    @Test
    void page_confirmed_carriesAbsoluteQrUrlAndDetails() {
        Booking b = booking(Booking.BookingStatus.CONFIRMED);
        when(repo.findByIdWithItems(b.getId())).thenReturn(Optional.of(b));

        String html = service.ticketPageHtml(b.getId(), BASE).orElseThrow();

        assertThat(html)
                .contains("INN-20260610-A1B2C3")
                .contains("VIP")
                .contains("20260610-T1")
                .contains(BASE + "/bookings/" + b.getId() + "/tickets/20260610-T1/qr");
    }

    @Test
    void page_pending_isEmpty() {
        Booking b = booking(Booking.BookingStatus.PENDING);
        when(repo.findByIdWithItems(b.getId())).thenReturn(Optional.of(b));

        assertThat(service.ticketPageHtml(b.getId(), BASE)).isEmpty();
    }

    @Test
    void emailHtml_isInlineStyled_withAbsoluteQrImageAndViewLink() {
        Booking b = booking(Booking.BookingStatus.CONFIRMED);

        String html = service.confirmationEmailHtml(b, BASE);

        // Inline styles (Gmail strips <style> blocks) + absolute hosted image
        // (data-URIs are stripped) + the view link. Both keyed by link token:
        // the booking's id appears nowhere in what we send.
        assertThat(html).contains("style=")
                .doesNotContain("<style>")
                .contains("src=\"" + BASE + "/bookings/ticket/" + ITEM_TOKEN + "/qr.png\"")
                .contains("href=\"" + BASE + "/bookings/view/" + BOOKING_TOKEN + "/tickets\"")
                .contains("INN-20260610-A1B2C3")
                .contains("20260610-T1")
                .doesNotContain(b.getId().toString());
    }

    // -- price tiers (V26): the ticket says what it was sold as ---------------

    @Test
    void pageAndEmail_printTheTierTheTicketWasSoldAt() {
        Booking b = booking(Booking.BookingStatus.CONFIRMED);
        b.getItems().get(0).setPriceTierName("Early bird");
        when(repo.findByIdWithItems(b.getId())).thenReturn(Optional.of(b));

        assertThat(service.ticketPageHtml(b.getId(), BASE).orElseThrow()).contains("VIP (Early bird)");
        assertThat(service.confirmationEmailHtml(b, BASE)).contains("VIP (Early bird)");
    }

    @Test
    void aTicketAtTheFullPrice_printsTheCategoryAlone() {
        Booking b = booking(Booking.BookingStatus.CONFIRMED);
        when(repo.findByIdWithItems(b.getId())).thenReturn(Optional.of(b));

        assertThat(service.ticketPageHtml(b.getId(), BASE).orElseThrow())
                .contains(">VIP<")
                .doesNotContain("VIP (");
    }

    @Test
    void theReceipt_breaksThePriceDownByTier_onlyWhenATierWasUsed() {
        BookingItem early1 = item("VIP", "Early bird", "100.00");
        BookingItem early2 = item("VIP", "Early bird", "100.00");
        BookingItem full = item("VIP", null, "150.00");

        assertThat(TicketDeliveryService.priceBreakdown(List.of(early1, early2, full)))
                .isEqualTo("2 x VIP (Early bird) at 100.00, 1 x VIP at 150.00");
        assertThat(TicketDeliveryService.priceBreakdown(List.of(full))).isNull();
    }

    private static BookingItem item(String category, String tier, String price) {
        BookingItem item = new BookingItem();
        item.setCategoryName(category);
        item.setPriceTierName(tier);
        item.setPriceAtBooking(new BigDecimal(price));
        return item;
    }

    // -- V22: whose ticket is this ------------------------------------------

    @Test
    void page_showsTheNamedAttendee_onTheirTicket() {
        Booking b = booking(Booking.BookingStatus.CONFIRMED);
        b.setCustomerName("Alice Moyo");
        b.getItems().get(0).setAttendeeName("Tendai Ncube");
        b.getItems().get(0).setBooking(b);
        when(repo.findByIdWithItems(b.getId())).thenReturn(Optional.of(b));

        String html = service.ticketPageHtml(b.getId(), BASE).orElseThrow();

        assertThat(html).contains("Ticket for Tendai Ncube");
        // The buyer's name must not be printed as the holder of a guest's ticket.
        assertThat(html).doesNotContain("Ticket for Alice Moyo");
    }

    @Test
    void page_fallsBackToThePurchaser_whenNoAttendeeIsNamed() {
        Booking b = booking(Booking.BookingStatus.CONFIRMED);
        b.setCustomerName("Alice Moyo");
        b.getItems().get(0).setBooking(b);
        when(repo.findByIdWithItems(b.getId())).thenReturn(Optional.of(b));

        String html = service.ticketPageHtml(b.getId(), BASE).orElseThrow();

        assertThat(html).contains("Ticket for Alice Moyo");
    }

    @Test
    void page_omitsTheHolderLine_onPreV22BookingsWithNoName() {
        Booking b = booking(Booking.BookingStatus.CONFIRMED);
        b.getItems().get(0).setBooking(b);
        when(repo.findByIdWithItems(b.getId())).thenReturn(Optional.of(b));

        String html = service.ticketPageHtml(b.getId(), BASE).orElseThrow();

        assertThat(html).doesNotContain("Ticket for ");
    }

    @Test
    void emailHtml_showsTheHolder_andEscapesIt() {
        Booking b = booking(Booking.BookingStatus.CONFIRMED);
        b.getItems().get(0).setAttendeeName("<b>Tendai</b>");
        b.getItems().get(0).setBooking(b);

        String html = service.confirmationEmailHtml(b, BASE);

        assertThat(html).contains("Ticket for &lt;b&gt;Tendai&lt;/b&gt;").doesNotContain("<b>Tendai</b>");
    }

    @Test
    void rendering_escapesInterpolatedValues() {
        Booking b = booking(Booking.BookingStatus.CONFIRMED);
        b.setConfirmationNumber("INN-<script>");
        when(repo.findByIdWithItems(b.getId())).thenReturn(Optional.of(b));

        String html = service.ticketPageHtml(b.getId(), BASE).orElseThrow();

        assertThat(html).contains("INN-&lt;script&gt;").doesNotContain("INN-<script>");
    }

    // -- one ticket, for its holder: nothing on it opens the booking ----------

    /** A CONFIRMED booking with two tickets: the buyer's own, and a named guest's. */
    private static Booking twoTicketBooking(Booking.BookingStatus status) {
        Booking b = booking(status);
        b.setCustomerName("Alice Moyo");
        BookingItem own = b.getItems().get(0);
        own.setId(UUID.randomUUID());
        own.setBooking(b);
        BookingItem guest = new BookingItem();
        guest.setId(UUID.randomUUID());
        guest.setLinkToken(GUEST_TOKEN);
        guest.setTicketNumber("20260610-GUEST");
        guest.setCategoryName("VIP");
        guest.setRowLabel("A");
        guest.setSeatNumber(13);
        guest.setAttendeeName("Tendai Ncube");
        guest.setBooking(b);
        b.setItems(List.of(own, guest));
        return b;
    }

    @Test
    void holderPage_showsOnlyThatTicket_andNothingThatOpensTheBooking() {
        Booking b = twoTicketBooking(Booking.BookingStatus.CONFIRMED);
        BookingItem guest = b.getItems().get(1);
        when(itemRepo.findByIdWithBooking(guest.getId())).thenReturn(Optional.of(guest));

        String html = service.holderTicketPageHtml(guest.getId(), BASE).orElseThrow();

        assertThat(html)
                .contains("Ticket for Tendai Ncube")
                .contains("20260610-GUEST")
                .contains("Row A, Seat 13")
                .contains("src=\"" + BASE + "/bookings/tickets/" + guest.getId() + "/qr.png\"");
        // The booking's id opens every guest's QR, so it appears nowhere: not
        // in a link, not in an image address. Nor do the other tickets, the
        // booking reference or what the buyer paid.
        assertThat(html)
                .doesNotContain(b.getId().toString())
                .doesNotContain("20260610-T1")
                .doesNotContain(b.getItems().get(0).getId().toString())
                .doesNotContain("INN-20260610-A1B2C3")
                .doesNotContain("Total paid")
                .doesNotContain("50.00");
    }

    @Test
    void holderPage_unconfirmedUnknownOrNull_isEmpty() {
        Booking pending = twoTicketBooking(Booking.BookingStatus.PENDING);
        BookingItem guest = pending.getItems().get(1);
        when(itemRepo.findByIdWithBooking(guest.getId())).thenReturn(Optional.of(guest));
        UUID unknown = UUID.randomUUID();
        when(itemRepo.findByIdWithBooking(unknown)).thenReturn(Optional.empty());

        assertThat(service.holderTicketPageHtml(guest.getId(), BASE)).isEmpty();
        assertThat(service.holderTicketPageHtml(unknown, BASE)).isEmpty();
        assertThat(service.holderTicketPageHtml(null, BASE)).isEmpty();
    }

    @Test
    void holderQr_isTheQrOfThatTicket_forAConfirmedBookingOnly() {
        Booking b = twoTicketBooking(Booking.BookingStatus.CONFIRMED);
        BookingItem guest = b.getItems().get(1);
        when(itemRepo.findByIdWithBooking(guest.getId())).thenReturn(Optional.of(guest));

        assertThat(service.holderTicketQrPng(guest.getId()))
                .hasValueSatisfying(png -> assertThat(png).isEqualTo(new QrCodeGenerator().toPngBytes("20260610-GUEST")));

        b.setStatus(Booking.BookingStatus.CANCELLED);
        assertThat(service.holderTicketQrPng(guest.getId())).isEmpty();
    }

    // -- V31: link tokens --------------------------------------------------

    @Test
    void thePaths_weSend_areKeyedByLinkToken_andNameNeitherTheBookingNorTheTicketNumber() {
        Booking b = booking(Booking.BookingStatus.CONFIRMED);
        BookingItem item = b.getItems().get(0);

        assertThat(TicketRenderingService.bookingPagePath(b)).isEqualTo("/bookings/view/" + BOOKING_TOKEN + "/tickets");
        assertThat(TicketRenderingService.holderTicketPath(item)).isEqualTo("/bookings/ticket/" + ITEM_TOKEN);
        assertThat(TicketRenderingService.ticketQrPath(item)).isEqualTo("/bookings/ticket/" + ITEM_TOKEN + "/qr.png");
        assertThat(TicketRenderingService.ticketQrPath(item))
                .doesNotContain(b.getId().toString())
                .doesNotContain(item.getId().toString())
                .doesNotContain("20260610-T1");
    }

    @Test
    void tokenPage_imagesAreKeyedByEachTicketsToken_andNoIdAppears() {
        Booking b = twoTicketBooking(Booking.BookingStatus.CONFIRMED);
        when(repo.findByLinkTokenWithItems(BOOKING_TOKEN)).thenReturn(Optional.of(b));

        String html = service.ticketPageHtmlByToken(BOOKING_TOKEN, BASE).orElseThrow();

        assertThat(html)
                .contains("src=\"" + BASE + "/bookings/ticket/" + ITEM_TOKEN + "/qr.png\"")
                .contains("src=\"" + BASE + "/bookings/ticket/" + GUEST_TOKEN + "/qr.png\"")
                .contains("20260610-T1")
                .contains("20260610-GUEST")
                .doesNotContain(b.getId().toString())
                .doesNotContain(b.getItems().get(0).getId().toString())
                .doesNotContain(b.getItems().get(1).getId().toString());
    }

    @Test
    void idPage_rendersAsBefore_andNeverEmbedsAToken() {
        // Anyone holding an id (staff, a scan report, a log) could otherwise
        // collect the tokens and keep the tickets after the id routes close.
        Booking b = twoTicketBooking(Booking.BookingStatus.CONFIRMED);
        when(repo.findByIdWithItems(b.getId())).thenReturn(Optional.of(b));
        BookingItem guest = b.getItems().get(1);
        when(itemRepo.findByIdWithBooking(guest.getId())).thenReturn(Optional.of(guest));

        String page = service.ticketPageHtml(b.getId(), BASE).orElseThrow();
        String holder = service.holderTicketPageHtml(guest.getId(), BASE).orElseThrow();

        assertThat(page)
                .contains(BASE + "/bookings/" + b.getId() + "/tickets/20260610-T1/qr")
                .doesNotContain(BOOKING_TOKEN).doesNotContain(ITEM_TOKEN).doesNotContain(GUEST_TOKEN);
        assertThat(holder).doesNotContain(GUEST_TOKEN).doesNotContain(BOOKING_TOKEN);
    }

    @Test
    void tokenPage_pendingOrUnknown_isEmpty() {
        Booking pending = booking(Booking.BookingStatus.PENDING);
        when(repo.findByLinkTokenWithItems(BOOKING_TOKEN)).thenReturn(Optional.of(pending));
        String unknown = "ffffffffffffffffffffffffffffffff";
        when(repo.findByLinkTokenWithItems(unknown)).thenReturn(Optional.empty());

        assertThat(service.ticketPageHtmlByToken(BOOKING_TOKEN, BASE)).isEmpty();
        assertThat(service.ticketPageHtmlByToken(unknown, BASE)).isEmpty();
    }

    @Test
    void holderTokenPage_showsOnlyThatTicket_andItsQrByToken() {
        Booking b = twoTicketBooking(Booking.BookingStatus.CONFIRMED);
        BookingItem guest = b.getItems().get(1);
        when(itemRepo.findByLinkTokenWithBooking(GUEST_TOKEN)).thenReturn(Optional.of(guest));

        String html = service.holderTicketPageHtmlByToken(GUEST_TOKEN, BASE).orElseThrow();

        assertThat(html)
                .contains("Ticket for Tendai Ncube")
                .contains("20260610-GUEST")
                .contains("src=\"" + BASE + "/bookings/ticket/" + GUEST_TOKEN + "/qr.png\"")
                .doesNotContain(b.getId().toString())
                .doesNotContain(BOOKING_TOKEN)
                .doesNotContain(ITEM_TOKEN)
                .doesNotContain("20260610-T1")
                .doesNotContain("INN-20260610-A1B2C3");
        assertThat(service.ticketQrPngByToken(GUEST_TOKEN))
                .hasValueSatisfying(png -> assertThat(png).isEqualTo(new QrCodeGenerator().toPngBytes("20260610-GUEST")));

        b.setStatus(Booking.BookingStatus.CANCELLED);
        assertThat(service.holderTicketPageHtmlByToken(GUEST_TOKEN, BASE)).isEmpty();
        assertThat(service.ticketQrPngByToken(GUEST_TOKEN)).isEmpty();
    }

    @Test
    void aMalformedToken_isEmpty_withoutAQuery() {
        for (String bad : new String[] {null, "", "0123456789ABCDEF0123456789ABCDEF", "0123", "../etc/passwd"}) {
            assertThat(service.ticketPageHtmlByToken(bad, BASE)).isEmpty();
            assertThat(service.holderTicketPageHtmlByToken(bad, BASE)).isEmpty();
            assertThat(service.ticketQrPngByToken(bad)).isEmpty();
        }
        verify(repo, never()).findByLinkTokenWithItems(anyString());
        verify(itemRepo, never()).findByLinkTokenWithBooking(anyString());
    }

    // -- IdLinkPolicy: the id routes close; the token routes never do --------

    @Test
    void closed_everyIdRouteIs404_andTheTokenRoutesStillServe() {
        service.setIdLinkPolicy(new IdLinkPolicy(IdLinkPolicy.Mode.CLOSED, null, null));
        Booking b = twoTicketBooking(Booking.BookingStatus.CONFIRMED);
        BookingItem guest = b.getItems().get(1);
        when(repo.findByIdWithItems(b.getId())).thenReturn(Optional.of(b));
        when(itemRepo.findByIdWithBooking(guest.getId())).thenReturn(Optional.of(guest));
        when(repo.findByLinkTokenWithItems(BOOKING_TOKEN)).thenReturn(Optional.of(b));
        when(itemRepo.findByLinkTokenWithBooking(GUEST_TOKEN)).thenReturn(Optional.of(guest));

        assertThat(service.ticketPageHtml(b.getId(), BASE)).isEmpty();
        assertThat(service.ticketQrPng(b.getId(), "20260610-T1")).isEmpty();
        assertThat(service.holderTicketPageHtml(guest.getId(), BASE)).isEmpty();
        assertThat(service.holderTicketQrPng(guest.getId())).isEmpty();

        assertThat(service.ticketPageHtmlByToken(BOOKING_TOKEN, BASE)).isPresent();
        assertThat(service.holderTicketPageHtmlByToken(GUEST_TOKEN, BASE)).isPresent();
        assertThat(service.ticketQrPngByToken(GUEST_TOKEN)).isPresent();
    }

    @Test
    void legacy_servesTheIdRoutes_onlyForBookingsCreatedBeforeTheCutOver() {
        service.setIdLinkPolicy(new IdLinkPolicy(IdLinkPolicy.Mode.LEGACY,
                LocalDateTime.of(2026, 11, 10, 8, 0), null));
        Booking old = twoTicketBooking(Booking.BookingStatus.CONFIRMED);
        old.setCreatedAt(LocalDateTime.of(2026, 11, 10, 7, 59));
        Booking fresh = twoTicketBooking(Booking.BookingStatus.CONFIRMED);
        fresh.setId(UUID.randomUUID());
        fresh.setCreatedAt(LocalDateTime.of(2026, 11, 10, 8, 0));
        when(repo.findByIdWithItems(old.getId())).thenReturn(Optional.of(old));
        when(repo.findByIdWithItems(fresh.getId())).thenReturn(Optional.of(fresh));
        when(itemRepo.findByIdWithBooking(any())).thenAnswer(inv -> {
            UUID id = inv.getArgument(0);
            return java.util.stream.Stream.of(old, fresh).flatMap(x -> x.getItems().stream())
                    .filter(i -> id.equals(i.getId())).findFirst();
        });

        assertThat(service.ticketPageHtml(old.getId(), BASE)).isPresent();
        assertThat(service.holderTicketQrPng(old.getItems().get(1).getId())).isPresent();
        assertThat(service.ticketPageHtml(fresh.getId(), BASE)).isEmpty();
        assertThat(service.ticketQrPng(fresh.getId(), "20260610-T1")).isEmpty();
        assertThat(service.holderTicketPageHtml(fresh.getItems().get(1).getId(), BASE)).isEmpty();
    }
}
