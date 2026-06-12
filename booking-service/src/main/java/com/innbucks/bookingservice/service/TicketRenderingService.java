package com.innbucks.bookingservice.service;

import com.innbucks.bookingservice.entity.Booking;
import com.innbucks.bookingservice.entity.BookingItem;
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
 * to: a scannable QR PNG per ticket, and a self-contained HTML ticket page for
 * the whole booking. Both are derived (the QR is just the ticket number
 * encoded), so nothing extra is stored.
 *
 * <p>Only <b>CONFIRMED</b> bookings render — a pending/cancelled booking must
 * never hand out a scannable ticket. Access control is the unguessable booking
 * UUID in the URL (tickets are bearer instruments — the link IS the ticket,
 * same trust model as forwarding the confirmation email); gate validation of
 * the scanned code is a separate concern.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TicketRenderingService {

    private final BookingRepository bookingRepository;
    private final QrCodeGenerator qrCodeGenerator;

    /** PNG bytes of the QR for one ticket of a CONFIRMED booking, or empty. */
    @Transactional(readOnly = true)
    public Optional<byte[]> ticketQrPng(UUID bookingId, String ticketNumber) {
        Booking booking = bookingRepository.findById(bookingId).orElse(null);
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
        Booking booking = bookingRepository.findById(bookingId).orElse(null);
        if (booking == null || booking.getStatus() != Booking.BookingStatus.CONFIRMED) {
            return Optional.empty();
        }
        return Optional.of(renderHtml(booking, publicBaseUrl));
    }

    private String renderHtml(Booking booking, String baseUrl) {
        StringBuilder sb = new StringBuilder(2048);
        sb.append("<!DOCTYPE html><html lang=\"en\"><head><meta charset=\"utf-8\">")
                .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
                .append("<title>InnBucks Tickets — ").append(esc(booking.getConfirmationNumber())).append("</title>")
                .append("<style>")
                .append("body{font-family:-apple-system,Segoe UI,Roboto,Helvetica,Arial,sans-serif;")
                .append("background:#0b1f33;color:#fff;margin:0;padding:24px}")
                .append(".wrap{max-width:480px;margin:0 auto}")
                .append("h1{font-size:20px;margin:0 0 4px}.sub{color:#cbd5e1;font-size:14px;margin:0 0 20px}")
                .append(".tkt{background:#13283f;border-radius:14px;padding:20px;margin-bottom:16px;text-align:center}")
                .append(".tkt img{width:220px;height:220px;background:#fff;padding:10px;border-radius:10px}")
                .append(".cat{font-weight:600;font-size:16px;margin:12px 0 2px}")
                .append(".seat{color:#cbd5e1;font-size:14px}")
                .append(".tn{font-family:monospace;font-size:13px;color:#93c5fd;margin-top:6px}")
                .append(".tot{color:#cbd5e1;font-size:14px;text-align:center;margin-top:8px}")
                .append("</style></head><body><div class=\"wrap\">");
        sb.append("<h1>Your tickets are confirmed</h1>")
                .append("<p class=\"sub\">Booking ").append(esc(booking.getConfirmationNumber()))
                .append(" — show each QR at the gate.</p>");

        for (BookingItem item : items(booking)) {
            String tn = item.getTicketNumber();
            sb.append("<div class=\"tkt\">")
                    .append("<img alt=\"QR for ticket ").append(esc(tn)).append("\" src=\"")
                    .append(esc(baseUrl)).append("/bookings/").append(booking.getId())
                    .append("/tickets/").append(esc(tn)).append("/qr\">")
                    .append("<div class=\"cat\">").append(esc(nullSafe(item.getCategoryName(), "Ticket"))).append("</div>");
            if (item.getRowLabel() != null || item.getSeatNumber() != null) {
                sb.append("<div class=\"seat\">Row ").append(esc(nullSafe(item.getRowLabel(), "-")))
                        .append(", Seat ").append(item.getSeatNumber() == null ? "-" : item.getSeatNumber())
                        .append("</div>");
            }
            sb.append("<div class=\"tn\">").append(esc(tn)).append("</div></div>");
        }
        if (booking.getTotalAmount() != null) {
            sb.append("<p class=\"tot\">Total paid: ").append(esc(booking.getTotalAmount().toPlainString()))
                    .append("</p>");
        }
        sb.append("</div></body></html>");
        return sb.toString();
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
                    .append(booking.getId()).append("/tickets/").append(esc(tn)).append("/qr\">")
                    .append("<div style=\"font-weight:bold;margin-top:8px\">")
                    .append(esc(nullSafe(item.getCategoryName(), "Ticket"))).append("</div>");
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
