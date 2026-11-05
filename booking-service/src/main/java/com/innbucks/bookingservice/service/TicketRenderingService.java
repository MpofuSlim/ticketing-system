package com.innbucks.bookingservice.service;

import com.innbucks.bookingservice.entity.Booking;
import com.innbucks.bookingservice.entity.BookingItem;
import com.innbucks.bookingservice.repository.BookingItemRepository;
import com.innbucks.bookingservice.repository.BookingRepository;
import com.innbucks.bookingservice.util.Money;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/**
 * Renders the customer-facing ticket artifacts the notification channels link
 * to: a scannable QR PNG per ticket, a self-contained HTML ticket page for the
 * whole booking (the purchaser's), and a page with one ticket on it (a named
 * guest's). All are derived (the QR is just the ticket number encoded), so
 * nothing extra is stored.
 *
 * <p>Only <b>CONFIRMED</b> bookings render — a pending/cancelled booking must
 * never hand out a scannable ticket. Access control is the random link token
 * in the URL (V31, {@link LinkTokens}): tickets are bearer instruments, the
 * link IS the ticket, same trust model as forwarding the confirmation email.
 * The booking's token opens every ticket on it, so it goes only to the
 * purchaser; a guest gets their ticket's own token ({@link #holderTicketPath}),
 * and nothing on the guest's page names the booking.
 *
 * <p>The same pages are still served by record id until
 * {@link IdLinkPolicy} closes those routes. A page opened by id renders
 * exactly as before V31 and never embeds a token: anyone holding an id (staff,
 * a scan report, a log) could otherwise collect the token and keep the page
 * after the id routes close.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TicketRenderingService {

    private final BookingRepository bookingRepository;
    private final QrCodeGenerator qrCodeGenerator;
    private final BookingItemRepository bookingItemRepository;

    private volatile IdLinkPolicy idLinks = IdLinkPolicy.OPEN;

    /** The cell's currency, shown with the total on the ticket pages ({@link Money}). */
    @Value("${innbucks.currency:USD}")
    private String currency = "USD";

    @Autowired(required = false)
    public void setIdLinkPolicy(IdLinkPolicy idLinks) {
        this.idLinks = Objects.requireNonNull(idLinks, "idLinks");
    }

    // ------------------------------------------------------------------ paths
    // Every link we send is built here, from the link tokens. Never from a
    // record id: an id is on staff screens and in logs, a token only in what
    // the buyer and the holder receive.

    /** The buyer's page, every ticket's QR on it: {@code /bookings/view/{token}/tickets}. */
    public static String bookingPagePath(Booking booking) {
        return "/bookings/view/" + booking.getLinkToken() + "/tickets";
    }

    /**
     * The path of ONE ticket's page, for its holder:
     * {@code /bookings/ticket/{token}}. Nothing in it names the booking. A
     * guest's message links here, never to the booking's page, which shows
     * every ticket's QR: a guest who could reach that page could enter on
     * another guest's ticket.
     */
    public static String holderTicketPath(BookingItem item) {
        return "/bookings/ticket/" + item.getLinkToken();
    }

    /**
     * One ticket's QR image: {@code /bookings/ticket/{token}/qr.png}. The
     * image in every email and page, and the WhatsApp QR's media path. It
     * carries neither the booking id nor the ticket number.
     */
    public static String ticketQrPath(BookingItem item) {
        return holderTicketPath(item) + "/qr.png";
    }

    // ------------------------------------------------------ by link token (V31)

    /** The buyer's page for a CONFIRMED booking, by its link token; else empty. */
    @Transactional(readOnly = true)
    public Optional<String> ticketPageHtmlByToken(String token, String publicBaseUrl) {
        if (!LinkTokens.isWellFormed(token)) {
            return Optional.empty();
        }
        return bookingRepository.findByLinkTokenWithItems(token)
                .filter(b -> b.getStatus() == Booking.BookingStatus.CONFIRMED)
                .map(b -> renderHtml(b,
                        item -> esc(publicBaseUrl) + esc(ticketQrPath(item))));
    }

    /** One ticket's page for its holder, by the ticket's link token; else empty. */
    @Transactional(readOnly = true)
    public Optional<String> holderTicketPageHtmlByToken(String token, String publicBaseUrl) {
        return confirmedItemByToken(token).map(item -> renderHolderHtml(item,
                esc(publicBaseUrl) + esc(ticketQrPath(item))));
    }

    /** One ticket's QR PNG, by the ticket's link token; else empty. */
    @Transactional(readOnly = true)
    public Optional<byte[]> ticketQrPngByToken(String token) {
        return confirmedItemByToken(token)
                .map(item -> qrCodeGenerator.toPngBytes(item.getTicketNumber()));
    }

    private Optional<BookingItem> confirmedItemByToken(String token) {
        if (!LinkTokens.isWellFormed(token)) {
            return Optional.empty();
        }
        return bookingItemRepository.findByLinkTokenWithBooking(token).filter(TicketRenderingService::renderable);
    }

    // ------------------------------------------- by record id (IdLinkPolicy)

    /**
     * PNG bytes of the QR for one ticket of a CONFIRMED booking, by the
     * booking's id and the ticket number, or empty. Only links sent before
     * V31 use it; it follows {@link IdLinkPolicy} like the other id routes.
     */
    @Transactional(readOnly = true)
    public Optional<byte[]> ticketQrPng(UUID bookingId, String ticketNumber) {
        Booking booking = bookingRepository.findByIdWithItems(bookingId).orElse(null);
        if (booking == null || booking.getStatus() != Booking.BookingStatus.CONFIRMED) {
            return Optional.empty();
        }
        boolean belongs = items(booking).stream()
                .anyMatch(i -> ticketNumber != null && ticketNumber.equals(i.getTicketNumber()));
        if (!belongs || !idLinks.allows(booking)) {
            return Optional.empty();
        }
        return Optional.ofNullable(qrCodeGenerator.toPngBytes(ticketNumber));
    }

    /**
     * The buyer's page by the booking's id, for a CONFIRMED booking the id
     * links still serve; else empty. Renders as before V31: its images are
     * keyed by the booking id and ticket number, never by a token.
     */
    @Transactional(readOnly = true)
    public Optional<String> ticketPageHtml(UUID bookingId, String publicBaseUrl) {
        Booking booking = bookingRepository.findByIdWithItems(bookingId).orElse(null);
        if (booking == null || booking.getStatus() != Booking.BookingStatus.CONFIRMED
                || !idLinks.allows(booking)) {
            return Optional.empty();
        }
        return Optional.of(renderHtml(booking,
                item -> esc(publicBaseUrl) + "/bookings/" + booking.getId()
                        + "/tickets/" + esc(item.getTicketNumber()) + "/qr"));
    }

    /**
     * One ticket's QR by the ticket's row id, for a CONFIRMED booking the id
     * links still serve; else empty.
     */
    @Transactional(readOnly = true)
    public Optional<byte[]> holderTicketQrPng(UUID itemId) {
        return confirmedItemById(itemId)
                .map(item -> qrCodeGenerator.toPngBytes(item.getTicketNumber()));
    }

    /**
     * One ticket's page by the ticket's row id, for a CONFIRMED booking the id
     * links still serve; else empty. Renders as before V31: its image is keyed
     * by the item id, never by a token.
     */
    @Transactional(readOnly = true)
    public Optional<String> holderTicketPageHtml(UUID itemId, String publicBaseUrl) {
        return confirmedItemById(itemId).map(item -> renderHolderHtml(item,
                esc(publicBaseUrl) + "/bookings/tickets/" + item.getId() + "/qr.png"));
    }

    private Optional<BookingItem> confirmedItemById(UUID itemId) {
        BookingItem item = itemId == null ? null : bookingItemRepository.findByIdWithBooking(itemId).orElse(null);
        if (item == null || !renderable(item) || !idLinks.allows(item.getBooking())) {
            return Optional.empty();
        }
        return Optional.of(item);
    }

    private static boolean renderable(BookingItem item) {
        return item.getBooking() != null
                && item.getBooking().getStatus() == Booking.BookingStatus.CONFIRMED
                && item.getTicketNumber() != null && !item.getTicketNumber().isBlank();
    }

    // -------------------------------------------------------------- rendering

    /** {@code qrSrc} gives each ticket's image URL, already escaped. */
    private String renderHtml(Booking booking, Function<BookingItem, String> qrSrc) {
        StringBuilder sb = new StringBuilder(2048);
        appendHead(sb, "InnBucks Tickets — " + esc(booking.getConfirmationNumber()));
        sb.append("<h1>Your tickets are confirmed</h1>")
                .append("<p class=\"sub\">Booking ").append(esc(booking.getConfirmationNumber()))
                .append(" — show each QR at the gate.</p>");

        for (BookingItem item : items(booking)) {
            appendTicket(sb, item, qrSrc.apply(item));
        }
        if (booking.getTotalAmount() != null) {
            sb.append("<p class=\"tot\">Total paid: ").append(esc(Money.display(booking.getTotalAmount(), currency)))
                    .append("</p>");
        }
        sb.append("</div></body></html>");
        return sb.toString();
    }

    /** {@code qrSrc} is the ticket's image URL, already escaped. */
    private String renderHolderHtml(BookingItem item, String qrSrc) {
        StringBuilder sb = new StringBuilder(1536);
        appendHead(sb, "InnBucks Ticket");
        sb.append("<h1>Your ticket</h1>")
                .append("<p class=\"sub\">Show this QR at the gate.</p>");
        appendTicket(sb, item, qrSrc);
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
        // Add-ons bought with this ticket (V33): what the holder shows at the car park or bar.
        if (item.addOnsLabel() != null) {
            sb.append("<div class=\"seat\">With ").append(esc(item.addOnsLabel())).append("</div>");
        }
        // Its assigned number (V34), e.g. the bib a runner wears: a label, not the entry credential.
        if (item.numberText() != null) {
            sb.append("<div class=\"cat\">").append(esc(item.numberText())).append("</div>");
        }
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
                    .append(esc(tn)).append("\" src=\"").append(esc(base))
                    .append(esc(ticketQrPath(item))).append("\">");
            String holder = item.holderName();
            if (holder != null && !holder.isBlank()) {
                sb.append("<div style=\"color:#0b1f33;margin-top:8px\">Ticket for ")
                        .append(esc(holder)).append("</div>");
            }
            sb.append("<div style=\"font-weight:bold;margin-top:8px\">")
                    .append(esc(nullSafe(item.ticketTypeLabel(), "Ticket"))).append("</div>");
            if (item.addOnsLabel() != null) {
                sb.append("<div style=\"color:#475569;font-size:14px\">With ")
                        .append(esc(item.addOnsLabel())).append("</div>");
            }
            if (item.numberText() != null) {
                sb.append("<div style=\"font-weight:bold;margin-top:4px\">")
                        .append(esc(item.numberText())).append("</div>");
            }
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
                    .append(esc(Money.display(booking.getTotalAmount(), currency))).append("</p>");
        }
        sb.append("<p><a href=\"").append(esc(base)).append(esc(bookingPagePath(booking)))
                .append("\">View your tickets online</a></p></div>");
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
