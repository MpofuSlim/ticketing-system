package com.innbucks.bookingservice.service;

import com.innbucks.bookingservice.entity.Booking;
import com.innbucks.bookingservice.entity.BookingItem;
import com.innbucks.bookingservice.repository.BookingItemRepository;
import com.innbucks.bookingservice.repository.BookingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Renders the customer-facing ticket artifacts the notification channels link
 * to: a scannable QR PNG per ticket, a self-contained HTML ticket page for the
 * whole booking (the purchaser's), and a page with one ticket on it (a named
 * guest's). All are derived (the QR is just the ticket number encoded), so
 * nothing extra is stored.
 *
 * <p>Only <b>CONFIRMED</b> bookings render — a pending/cancelled booking must
 * never hand out a scannable ticket. Access control is the unguessable UUID in
 * the URL (tickets are bearer instruments — the link IS the ticket, same trust
 * model as forwarding the confirmation email); gate validation of the scanned
 * code is a separate concern. The booking's id opens every ticket on it, so it
 * goes only to the purchaser; a guest gets their ticket's own id
 * ({@link #holderTicketPath}), and nothing on the guest's page names the
 * booking.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TicketRenderingService {

    private final BookingRepository bookingRepository;
    private final QrCodeGenerator qrCodeGenerator;
    private final BookingItemRepository bookingItemRepository;

    /** PNG bytes of the QR for one ticket of a CONFIRMED booking, or empty. */
    @Transactional(readOnly = true)
    public Optional<byte[]> ticketQrPng(UUID bookingId, String ticketNumber) {
        Booking booking = bookingRepository.findByIdWithItems(bookingId).orElse(null);
        if (booking == null || booking.getStatus() != Booking.BookingStatus.CONFIRMED) {
            return Optional.empty();
        }
        boolean belongs = items(booking).stream()
                .anyMatch(i -> ticketNumber != null && ticketNumber.equals(i.getTicketNumber()));
        if (!belongs) {
            return Optional.empty();
        }
        return Optional.ofNullable(qrCodeGenerator.toPngBytes(ticketNumber));
    }

    /** Self-contained HTML ticket page for a CONFIRMED booking, or empty. */
    @Transactional(readOnly = true)
    public Optional<String> ticketPageHtml(UUID bookingId, String publicBaseUrl) {
        Booking booking = bookingRepository.findByIdWithItems(bookingId).orElse(null);
        if (booking == null || booking.getStatus() != Booking.BookingStatus.CONFIRMED) {
            return Optional.empty();
        }
        return Optional.of(renderHtml(booking, publicBaseUrl));
    }

    /**
     * The path of ONE ticket's page, for its holder: keyed by the ticket's own
     * row id, with no booking id in it. A guest's message links here, never to
     * the booking's page, which shows every ticket's QR: a guest who could reach
     * that page could enter on another guest's ticket.
     */
    public static String holderTicketPath(UUID itemId) {
        return "/bookings/tickets/" + itemId;
    }

    /**
     * PNG bytes of one ticket's QR, addressed by the ticket's row id, for a
     * CONFIRMED booking; else empty. The holder's page uses this rather than the
     * booking-keyed QR, so nothing on that page names the booking.
     */
    @Transactional(readOnly = true)
    public Optional<byte[]> holderTicketQrPng(UUID itemId) {
        return confirmedItem(itemId)
                .map(item -> qrCodeGenerator.toPngBytes(item.getTicketNumber()));
    }

    /**
     * A page with ONE ticket on it, for its holder (a named guest): the QR, who
     * it is for, its type and seat, and its number. Not the booking's reference,
     * total or other tickets. CONFIRMED bookings only, else empty.
     */
    @Transactional(readOnly = true)
    public Optional<String> holderTicketPageHtml(UUID itemId, String publicBaseUrl) {
        return confirmedItem(itemId).map(item -> renderHolderHtml(item, publicBaseUrl));
    }

    private Optional<BookingItem> confirmedItem(UUID itemId) {
        BookingItem item = itemId == null ? null : bookingItemRepository.findByIdWithBooking(itemId).orElse(null);
        if (item == null || item.getBooking() == null
                || item.getBooking().getStatus() != Booking.BookingStatus.CONFIRMED
                || item.getTicketNumber() == null || item.getTicketNumber().isBlank()) {
            return Optional.empty();
        }
        return Optional.of(item);
    }

    private String renderHtml(Booking booking, String baseUrl) {
        StringBuilder sb = new StringBuilder(2048);
        appendHead(sb, "InnBucks Tickets — " + esc(booking.getConfirmationNumber()));
        sb.append("<h1>Your tickets are confirmed</h1>")
                .append("<p class=\"sub\">Booking ").append(esc(booking.getConfirmationNumber()))
                .append(" — show each QR at the gate.</p>");

        for (BookingItem item : items(booking)) {
            appendTicket(sb, item, esc(baseUrl) + "/bookings/" + booking.getId()
                    + "/tickets/" + esc(item.getTicketNumber()) + "/qr");
        }
        if (booking.getTotalAmount() != null) {
            sb.append("<p class=\"tot\">Total paid: ").append(esc(booking.getTotalAmount().toPlainString()))
                    .append("</p>");
        }
        sb.append("</div></body></html>");
        return sb.toString();
    }

    private String renderHolderHtml(BookingItem item, String baseUrl) {
        StringBuilder sb = new StringBuilder(1536);
        appendHead(sb, "InnBucks Ticket");
        sb.append("<h1>Your ticket</h1>")
                .append("<p class=\"sub\">Show this QR at the gate.</p>");
        appendTicket(sb, item, esc(baseUrl) + holderTicketPath(item.getId()) + "/qr.png");
        sb.append("</div></body></html>");
        return sb.toString();
    }

    /** The page head and opening wrapper. {@code title} is already escaped. */
    private static void appendHead(StringBuilder sb, String title) {
        sb.append("<!DOCTYPE html><html lang=\"en\"><head><meta charset=\"utf-8\">")
                .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
                .append("<title>").append(title).append("</title>")
                .append("<style>")
                .append("body{font-family:-apple-system,Segoe UI,Roboto,Helvetica,Arial,sans-serif;")
                .append("background:#0b1f33;color:#fff;margin:0;padding:24px}")
                .append(".wrap{max-width:480px;margin:0 auto}")
                .append("h1{font-size:20px;margin:0 0 4px}.sub{color:#cbd5e1;font-size:14px;margin:0 0 20px}")
                .append(".tkt{background:#13283f;border-radius:14px;padding:20px;margin-bottom:16px;text-align:center}")
                .append(".tkt img{width:220px;height:220px;background:#fff;padding:10px;border-radius:10px}")
                .append(".who{color:#e2e8f0;font-size:15px;margin-top:10px}")
                .append(".cat{font-weight:600;font-size:16px;margin:12px 0 2px}")
                .append(".seat{color:#cbd5e1;font-size:14px}")
                .append(".tn{font-family:monospace;font-size:13px;color:#93c5fd;margin-top:6px}")
                .append(".tot{color:#cbd5e1;font-size:14px;text-align:center;margin-top:8px}")
                .append("</style></head><body><div class=\"wrap\">");
    }

    /** One ticket's card. {@code qrSrc} is already escaped. */
    private static void appendTicket(StringBuilder sb, BookingItem item, String qrSrc) {
        String tn = item.getTicketNumber();
        sb.append("<div class=\"tkt\">")
                .append("<img alt=\"QR for ticket ").append(esc(tn)).append("\" src=\"")
                .append(qrSrc).append("\">");
        // Who holds this ticket (V22): the named attendee, else the buyer.
        String holder = item.holderName();
        if (holder != null && !holder.isBlank()) {
            sb.append("<div class=\"who\">Ticket for ").append(esc(holder)).append("</div>");
        }
        sb.append("<div class=\"cat\">").append(esc(nullSafe(item.ticketTypeLabel(), "Ticket"))).append("</div>");
        if (item.getRowLabel() != null || item.getSeatNumber() != null) {
            sb.append("<div class=\"seat\">Row ").append(esc(nullSafe(item.getRowLabel(), "-")))
                    .append(", Seat ").append(item.getSeatNumber() == null ? "-" : item.getSeatNumber())
                    .append("</div>");
        }
        sb.append("<div class=\"tn\">").append(esc(tn)).append("</div></div>");
    }

    /**
     * Email-safe HTML (inline styles, table-free, hosted QR image URLs) for the
     * confirmation email body. Pure render over an already-loaded booking — the
     * caller (the confirm listener) holds the read tx, so {@code items} are
     * initialised. Gmail/Outlook strip &lt;style&gt; blocks and data-URIs, hence
     * inline styles + an absolute {@code <img src>} to the hosted QR endpoint.
     */
    public String confirmationEmailHtml(Booking booking, String baseUrl) {
        String base = baseUrl == null ? "" : baseUrl;
        StringBuilder sb = new StringBuilder(2048);
        sb.append("<div style=\"font-family:Arial,Helvetica,sans-serif;max-width:480px;")
                .append("margin:0 auto;color:#0b1f33\">")
                .append("<h2 style=\"margin:0 0 4px\">Your tickets are confirmed \uD83C\uDF9F\uFE0F</h2>")
                .append("<p style=\"color:#475569;margin:0 0 18px\">Booking <b>")
                .append(esc(booking.getConfirmationNumber()))
                .append("</b> — show each QR code at the gate.</p>");
        for (BookingItem item : items(booking)) {
            String tn = item.getTicketNumber();
            sb.append("<div style=\"border:1px solid #e2e8f0;border-radius:10px;padding:16px;")
                    .append("margin:0 0 12px;text-align:center\">")
                    .append("<img width=\"200\" height=\"200\" style=\"display:block;margin:0 auto\" alt=\"QR ")
                    .append(esc(tn)).append("\" src=\"").append(esc(base)).append("/bookings/")
                    .append(booking.getId()).append("/tickets/").append(esc(tn)).append("/qr\">");
            String holder = item.holderName();
            if (holder != null && !holder.isBlank()) {
                sb.append("<div style=\"color:#0b1f33;margin-top:8px\">Ticket for ")
                        .append(esc(holder)).append("</div>");
            }
            sb.append("<div style=\"font-weight:bold;margin-top:8px\">")
                    .append(esc(nullSafe(item.ticketTypeLabel(), "Ticket"))).append("</div>");
            if (item.getRowLabel() != null || item.getSeatNumber() != null) {
                sb.append("<div style=\"color:#475569;font-size:14px\">Row ")
                        .append(esc(nullSafe(item.getRowLabel(), "-"))).append(", Seat ")
                        .append(item.getSeatNumber() == null ? "-" : item.getSeatNumber()).append("</div>");
            }
            sb.append("<div style=\"font-family:monospace;font-size:13px;color:#2563eb;margin-top:4px\">")
                    .append(esc(tn)).append("</div></div>");
        }
        if (booking.getTotalAmount() != null) {
            sb.append("<p style=\"color:#475569\">Total paid: ")
                    .append(esc(booking.getTotalAmount().toPlainString())).append("</p>");
        }
        sb.append("<p><a href=\"").append(esc(base)).append("/bookings/").append(booking.getId())
                .append("/tickets\">View your tickets online</a></p></div>");
        return sb.toString();
    }

    private static List<BookingItem> items(Booking booking) {
        return booking.getItems() == null ? List.of() : booking.getItems();
    }

    private static String nullSafe(String v, String fallback) {
        return v == null || v.isBlank() ? fallback : v;
    }

    /** Minimal HTML escaping for the few booking-sourced strings we interpolate. */
    private static String esc(String v) {
        if (v == null) return "";
        return v.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }
}
