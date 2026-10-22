package com.innbucks.bookingservice.controller;

import com.innbucks.bookingservice.dto.ApiResult;
import com.innbucks.bookingservice.dto.EventLookupDTO;
import com.innbucks.bookingservice.entity.Booking;
import com.innbucks.bookingservice.entity.BookingItem;
import com.innbucks.bookingservice.testsupport.BookingStockPostgresTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The two routes that replace {@code GET /bookings/public/phone/{phone}}, through
 * the real filter chain against Postgres:
 * <ul>
 *   <li>{@code POST /bookings/ticket-check}: a yes/no for a partner holding the
 *       key, counting the purchaser and a named guest's own phone, CONFIRMED only;</li>
 *   <li>{@code GET /bookings/me/tickets}: the ticket wallet of the session's own
 *       phone. As a pattern, the public ticket-artifact permit
 *       ({@code /bookings/*}{@code /tickets/**}) also matches this path, so the
 *       401 without a token is the case that matters most.</li>
 * </ul>
 */
@TestPropertySource(properties = "app.booking.ticket-check-api-key=" + TicketCheckAndMyTicketsPostgresIT.KEY)
class TicketCheckAndMyTicketsPostgresIT extends BookingStockPostgresTestBase {

    static final String KEY = "it-ticket-check-key-0123456789";

    private final String buyer = "+26377" + (1_000_000 + (int) (Math.random() * 8_999_999));
    private final String guest = "+26378" + (1_000_000 + (int) (Math.random() * 8_999_999));
    private final List<UUID> seeded = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        for (UUID id : seeded) {
            jdbc.update("DELETE FROM booking_items WHERE booking_id = ?", id);
            jdbc.update("DELETE FROM bookings WHERE id = ?", id);
        }
    }

    // ---- POST /bookings/ticket-check ------------------------------------------

    @Test
    void ticketCheck_answersForThePurchaserAndANamedGuest_confirmedOnly() throws Exception {
        UUID event = UUID.randomUUID();
        seed(event, buyer, Booking.BookingStatus.CONFIRMED, guest);
        String pendingBuyer = "+26371" + (1_000_000 + (int) (Math.random() * 8_999_999));
        seed(event, pendingBuyer, Booking.BookingStatus.PENDING, null);

        check(buyer, event).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.eventId").value(event.toString()))
                .andExpect(jsonPath("$.data.hasTicket").value(true));
        // A named guest with their own phone holds a ticket too.
        check(guest, event).andExpect(jsonPath("$.data.hasTicket").value(true));
        // The local spelling of the same number.
        check("0" + buyer.substring(4), event).andExpect(jsonPath("$.data.hasTicket").value(true));
        // An unpaid hold is not a ticket.
        check(pendingBuyer, event).andExpect(jsonPath("$.data.hasTicket").value(false));
        // Another event is not this one.
        check(buyer, UUID.randomUUID()).andExpect(jsonPath("$.data.hasTicket").value(false));
    }

    @Test
    void ticketCheck_withoutTheKey_is401_beforeTheBodyIsRead() throws Exception {
        mvc.perform(post("/bookings/ticket-check").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phoneNumber\":\"not a phone\",\"eventId\":\"nope\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Missing or invalid X-Api-Key"));
        mvc.perform(post("/bookings/ticket-check").header("X-Api-Key", "wrong")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phoneNumber\":\"not a phone\",\"eventId\":\"nope\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void ticketCheck_withTheKey_namesTheBadField() throws Exception {
        mvc.perform(post("/bookings/ticket-check").header("X-Api-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phoneNumber\":\"12\",\"eventId\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("phoneNumber is not a valid phone number."));
        mvc.perform(post("/bookings/ticket-check").header("X-Api-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phoneNumber\":\"" + buyer + "\",\"eventId\":\"nope\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("eventId must be a UUID."));
    }

    // ---- GET /bookings/me/tickets ---------------------------------------------

    @Test
    void myTickets_withoutAToken_is401_notThePublicTicketPagePermit() throws Exception {
        mvc.perform(get("/bookings/me/tickets")).andExpect(status().isUnauthorized());
    }

    @Test
    void myTickets_returnsTheSessionPhonesWallet_inTheTicketWalletShape() throws Exception {
        UUID event = UUID.randomUUID();
        Booking mine = seed(event, buyer, Booking.BookingStatus.CONFIRMED, null);
        seed(UUID.randomUUID(), guest, Booking.BookingStatus.CONFIRMED, null);
        when(eventServiceClient.getEventInternal(eq(event), anyString())).thenReturn(ApiResult.ok(
                EventLookupDTO.builder().eventId(event).title("Harare Jazz Festival").venue("HICC")
                        .startDateTime(LocalDateTime.now(ZoneOffset.UTC).plusDays(3))
                        .endDateTime(LocalDateTime.now(ZoneOffset.UTC).plusDays(3).plusHours(5))
                        .build()));

        mvc.perform(get("/bookings/me/tickets").header("Authorization",
                        bearer("buyer@example.com", List.of("CUSTOMER"), Map.of("phoneNumber", buyer))))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].confirmationNumber").value(mine.getConfirmationNumber()))
                .andExpect(jsonPath("$.data[0].eventTitle").value("Harare Jazz Festival"))
                .andExpect(jsonPath("$.data[0].window").value("UPCOMING"))
                .andExpect(jsonPath("$.data[0].items[0].qrCode").isNotEmpty());

        mvc.perform(get("/bookings/me/tickets").param("filter", "PAST").header("Authorization",
                        bearer("buyer@example.com", List.of("CUSTOMER"), Map.of("phoneNumber", buyer))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(0)));
    }

    @Test
    void myTickets_forASessionWithNoPhone_is400() throws Exception {
        mvc.perform(get("/bookings/me/tickets").header("Authorization",
                        bearer("admin@innbucks.co.zw", List.of("SUPER_ADMIN"), Map.of())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value("Your session has no phone number. Sign in with your phone to see your tickets."));
    }

    // ---- helpers ----------------------------------------------------------------

    private org.springframework.test.web.servlet.ResultActions check(String phone, UUID eventId) throws Exception {
        return mvc.perform(post("/bookings/ticket-check").header("X-Api-Key", KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"phoneNumber\":\"" + phone + "\",\"eventId\":\"" + eventId + "\"}"));
    }

    /** A one-ticket booking for {@code eventId}, committed; the ticket names {@code attendeePhone} when given. */
    private Booking seed(UUID eventId, String phone, Booking.BookingStatus status, String attendeePhone) {
        return new TransactionTemplate(txManager).execute(s -> {
            Booking booking = Booking.builder()
                    .userEmail("buyer-" + UUID.randomUUID() + "@example.com")
                    .phoneNumber(phone)
                    .customerName("Rufaro Moyo")
                    .eventId(eventId)
                    .tenantUserUuid(UUID.randomUUID())
                    .confirmationNumber("INN-TC-" + UUID.randomUUID().toString().substring(0, 13).toUpperCase())
                    .status(status)
                    .totalAmount(new BigDecimal("10.00"))
                    .cashAmount(status == Booking.BookingStatus.CONFIRMED ? new BigDecimal("10.00") : null)
                    .expiresAt(status == Booking.BookingStatus.PENDING
                            ? LocalDateTime.now(ZoneOffset.UTC).plusMinutes(10) : null)
                    .build();
            bookingRepository.save(booking);
            BookingItem.BookingItemBuilder item = BookingItem.builder()
                    .booking(booking).seatId(UUID.randomUUID()).categoryId(UUID.randomUUID())
                    .rowLabel("GA").seatNumber(1).categoryName("General")
                    .priceAtBooking(new BigDecimal("10.00"))
                    .ticketNumber("TC-" + UUID.randomUUID())
                    .isActive(true);
            if (attendeePhone != null) {
                item.attendeeName("Guest One").attendeePhone(attendeePhone);
            }
            booking.setItems(List.of(bookingItemRepository.save(item.build())));
            seeded.add(booking.getId());
            return booking;
        });
    }
}
