package com.innbucks.bookingservice.service;

import com.innbucks.bookingservice.entity.Booking;
import com.innbucks.bookingservice.entity.BookingItem;
import com.innbucks.bookingservice.repository.BookingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pins the bearer-instrument rules of the ticket renderer: only CONFIRMED
 * bookings render, the QR is real PNG bytes, a ticket number must belong to
 * the booking, and the absolute hosted-QR URL is woven into both the page and
 * the email so they resolve from outside any app session.
 */
class TicketRenderingServiceTest {

    private static final String BASE = "https://api.test";

    private BookingRepository repo;
    private TicketRenderingService service;

    @BeforeEach
    void setUp() {
        repo = mock(BookingRepository.class);
        service = new TicketRenderingService(repo, new QrCodeGenerator());
    }

    private static Booking booking(Booking.BookingStatus status) {
        Booking b = new Booking();
        b.setId(UUID.randomUUID());
        b.setStatus(status);
        b.setConfirmationNumber("INN-20260610-A1B2C3");
        b.setTotalAmount(new BigDecimal("50.00"));
        BookingItem item = new BookingItem();
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
        when(repo.findById(b.getId())).thenReturn(Optional.of(b));

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
        when(repo.findById(b.getId())).thenReturn(Optional.of(b));

        assertThat(service.ticketQrPng(b.getId(), "SOMEONE-ELSES-TICKET")).isEmpty();
    }

    @Test
    void qr_pendingBooking_isEmpty_neverRendersUnpaidTicket() {
        Booking b = booking(Booking.BookingStatus.PENDING);
        when(repo.findById(b.getId())).thenReturn(Optional.of(b));

        assertThat(service.ticketQrPng(b.getId(), "20260610-T1")).isEmpty();
    }

    @Test
    void qr_unknownBooking_isEmpty() {
        UUID id = UUID.randomUUID();
        when(repo.findById(id)).thenReturn(Optional.empty());

        assertThat(service.ticketQrPng(id, "20260610-T1")).isEmpty();
    }

    @Test
    void page_confirmed_carriesAbsoluteQrUrlAndDetails() {
        Booking b = booking(Booking.BookingStatus.CONFIRMED);
        when(repo.findById(b.getId())).thenReturn(Optional.of(b));

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
        when(repo.findById(b.getId())).thenReturn(Optional.of(b));

        assertThat(service.ticketPageHtml(b.getId(), BASE)).isEmpty();
    }

    @Test
    void emailHtml_isInlineStyled_withAbsoluteQrImageAndViewLink() {
        Booking b = booking(Booking.BookingStatus.CONFIRMED);

        String html = service.confirmationEmailHtml(b, BASE);

        // Inline styles (Gmail strips <style> blocks) + absolute hosted image
        // (data-URIs are stripped) + the view link.
        assertThat(html).contains("style=")
                .doesNotContain("<style>")
                .contains("src=\"" + BASE + "/bookings/" + b.getId() + "/tickets/20260610-T1/qr\"")
                .contains(BASE + "/bookings/" + b.getId() + "/tickets")
                .contains("INN-20260610-A1B2C3")
                .contains("20260610-T1");
    }

    @Test
    void rendering_escapesInterpolatedValues() {
        Booking b = booking(Booking.BookingStatus.CONFIRMED);
        b.setConfirmationNumber("INN-<script>");
        when(repo.findById(b.getId())).thenReturn(Optional.of(b));

        String html = service.ticketPageHtml(b.getId(), BASE).orElseThrow();

        assertThat(html).contains("INN-&lt;script&gt;").doesNotContain("INN-<script>");
    }
}
