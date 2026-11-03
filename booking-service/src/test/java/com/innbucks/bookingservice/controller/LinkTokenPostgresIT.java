package com.innbucks.bookingservice.controller;

import com.innbucks.bookingservice.entity.Booking;
import com.innbucks.bookingservice.entity.BookingItem;
import com.innbucks.bookingservice.service.LinkTokens;
import com.innbucks.bookingservice.testsupport.BookingStockPostgresTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * V31 against real Postgres and the real filter chain: every booking and
 * ticket gets a link token of its own, a row the previous image writes without
 * the column still gets one from the default (the rolling update), and the
 * token routes answer an ANONYMOUS caller, the {@code /brand} QR alias the
 * WhatsApp gateway fetches included.
 */
class LinkTokenPostgresIT extends BookingStockPostgresTestBase {

    private final List<UUID> seeded = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        for (UUID id : seeded) {
            jdbc.update("DELETE FROM booking_items WHERE booking_id = ?", id);
            jdbc.update("DELETE FROM bookings WHERE id = ?", id);
        }
        deleteSeeded();
    }

    @Test
    void theCreateResponse_carriesTheViewToken_andItOpensTheBooking() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID categoryId = UUID.randomUUID();
        category(eventId, categoryId, "General", 10);

        String body = mvc.perform(post("/bookings").contentType(MediaType.APPLICATION_JSON).content("""
                        {"eventId":"%s","customerName":"Rufaro Moyo","phoneNumber":"+263771234567",
                         "seats":[{"categoryId":"%s"}]}
                        """.formatted(eventId, categoryId)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(com.jayway.jsonpath.JsonPath.read(body, "$.data.id"));
        seeded.add(id);
        String viewToken = com.jayway.jsonpath.JsonPath.read(body, "$.data.viewToken");

        assertThat(viewToken).matches(LinkTokens.PATTERN)
                .isEqualTo(jdbc.queryForObject("SELECT link_token FROM bookings WHERE id = ?", String.class, id));
        mvc.perform(get("/bookings/view/" + viewToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(id.toString()))
                .andExpect(jsonPath("$.data.status").value("PENDING"));
    }

    @Test
    void aSavedBookingAndItsTicket_eachGetATokenOfTheirOwn() {
        Booking booking = seed(Booking.BookingStatus.CONFIRMED);
        String bookingToken = jdbc.queryForObject("SELECT link_token FROM bookings WHERE id = ?",
                String.class, booking.getId());
        String itemToken = jdbc.queryForObject("SELECT link_token FROM booking_items WHERE booking_id = ?",
                String.class, booking.getId());

        assertThat(bookingToken).isEqualTo(booking.getLinkToken()).matches(LinkTokens.PATTERN);
        assertThat(itemToken).matches(LinkTokens.PATTERN).isNotEqualTo(bookingToken);
        assertThat(bookingRepository.findByLinkTokenWithItems(bookingToken))
                .hasValueSatisfying(b -> assertThat(b.getItems()).hasSize(1));
        assertThat(bookingItemRepository.findByLinkTokenWithBooking(itemToken))
                .hasValueSatisfying(i -> assertThat(i.getBooking().getId()).isEqualTo(booking.getId()));
    }

    @Test
    void aRowWrittenWithoutTheColumn_getsATokenFromTheDefault() {
        // What the previous image does during the rolling update: it knows
        // nothing of link_token. The insert must succeed, with a token of its own.
        Booking source = seed(Booking.BookingStatus.PENDING);
        List<String> columns = jdbc.queryForList("""
                SELECT column_name FROM information_schema.columns
                WHERE table_schema = current_schema() AND table_name = 'bookings'
                  AND column_name NOT IN ('id', 'link_token', 'confirmation_number')
                """, String.class);
        String list = String.join(", ", columns);
        UUID copy = UUID.randomUUID();
        jdbc.update("INSERT INTO bookings (id, confirmation_number, " + list + ") SELECT ?, ?, " + list
                + " FROM bookings WHERE id = ?", copy, "INN-LT-" + copy.toString().substring(0, 8), source.getId());
        seeded.add(copy);

        String token = jdbc.queryForObject("SELECT link_token FROM bookings WHERE id = ?", String.class, copy);
        assertThat(token).matches(LinkTokens.PATTERN).isNotEqualTo(source.getLinkToken());
    }

    @Test
    void theTokenRoutes_answerAnAnonymousCaller() throws Exception {
        Booking booking = seed(Booking.BookingStatus.CONFIRMED);
        BookingItem item = booking.getItems().get(0);

        mvc.perform(get("/bookings/view/" + booking.getLinkToken()))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.data.items[0].ticketNumber").value(item.getTicketNumber()))
                .andExpect(jsonPath("$.data.viewToken").doesNotExist());
        mvc.perform(get("/bookings/view/" + booking.getLinkToken() + "/tickets"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "/bookings/ticket/" + item.getLinkToken() + "/qr.png")))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(
                        booking.getId().toString()))));
        mvc.perform(get("/bookings/ticket/" + item.getLinkToken()))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML));
        for (String path : new String[]{
                "/bookings/ticket/" + item.getLinkToken() + "/qr",
                "/bookings/ticket/" + item.getLinkToken() + "/qr.png",
                "/brand/bookings/ticket/" + item.getLinkToken() + "/qr.png"}) {
            mvc.perform(get(path))
                    .andExpect(status().isOk())
                    .andExpect(content().contentType(MediaType.IMAGE_PNG));
        }
    }

    @Test
    void anUnknownToken_orAPendingBookingsPages_are404() throws Exception {
        Booking pending = seed(Booking.BookingStatus.PENDING);
        String unknown = LinkTokens.newToken();

        mvc.perform(get("/bookings/view/" + unknown)).andExpect(status().isNotFound());
        mvc.perform(get("/bookings/view/" + unknown + "/tickets")).andExpect(status().isNotFound());
        mvc.perform(get("/bookings/ticket/" + unknown)).andExpect(status().isNotFound());
        mvc.perform(get("/bookings/ticket/" + unknown + "/qr.png")).andExpect(status().isNotFound());
        // A pending booking's status is readable (checkout polls it); its
        // pages and QR never are.
        mvc.perform(get("/bookings/view/" + pending.getLinkToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.items[0].ticketNumber").doesNotExist());
        mvc.perform(get("/bookings/view/" + pending.getLinkToken() + "/tickets")).andExpect(status().isNotFound());
        mvc.perform(get("/bookings/ticket/" + pending.getItems().get(0).getLinkToken() + "/qr.png"))
                .andExpect(status().isNotFound());
    }

    /** A one-ticket booking, committed. */
    private Booking seed(Booking.BookingStatus status) {
        return new TransactionTemplate(txManager).execute(s -> {
            Booking booking = Booking.builder()
                    .userEmail("buyer-" + UUID.randomUUID() + "@example.com")
                    .phoneNumber("+263771234567")
                    .customerName("Rufaro Moyo")
                    .eventId(UUID.randomUUID())
                    .tenantUserUuid(UUID.randomUUID())
                    .confirmationNumber("INN-LT-" + UUID.randomUUID().toString().substring(0, 13).toUpperCase())
                    .status(status)
                    .totalAmount(new BigDecimal("10.00"))
                    .build();
            bookingRepository.save(booking);
            BookingItem item = bookingItemRepository.save(BookingItem.builder()
                    .booking(booking).seatId(UUID.randomUUID()).categoryId(UUID.randomUUID())
                    .rowLabel("GA").seatNumber(1).categoryName("General")
                    .priceAtBooking(new BigDecimal("10.00"))
                    .ticketNumber("LT-" + UUID.randomUUID())
                    .isActive(true)
                    .build());
            booking.setItems(List.of(item));
            seeded.add(booking.getId());
            return booking;
        });
    }
}
