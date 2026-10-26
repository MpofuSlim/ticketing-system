package com.innbucks.bookingservice.controller;

import com.innbucks.bookingservice.entity.Booking;
import com.innbucks.bookingservice.testsupport.BookingStockPostgresTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The per-number cap on unpaid bookings, through the real filter chain against
 * Postgres: five unpaid bookings for one phone in 15 minutes, and the sixth is
 * {@code 429 TOO_MANY_UNPAID_BOOKINGS} before anything is looked up or held.
 * Paid bookings and bookings older than the window never count.
 *
 * <p>The cap is off in the {@code it} profile (the stock and concurrency ITs
 * book many times for one phone); this class switches it back on.
 */
@TestPropertySource(properties = "app.booking.unpaid-per-number.max=5")
class UnpaidBookingsPerNumberPostgresIT extends BookingStockPostgresTestBase {

    private final List<UUID> seeded = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        for (UUID id : seeded) {
            jdbc.update("DELETE FROM bookings WHERE id = ?", id);
        }
    }

    @Test
    void fiveUnpaidBookings_makeTheSixthA429_andNothingIsCreated() throws Exception {
        String phone = randomPhone();
        for (int i = 0; i < 3; i++) {
            seed(phone, Booking.BookingStatus.PENDING);
        }
        // A cancelled booking still counts: cancelling and booking again must
        // not reset the number's allowance.
        seed(phone, Booking.BookingStatus.CANCELLED);
        seed(phone, Booking.BookingStatus.CANCELLED);

        book(phone)
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("429 TOO_MANY_REQUESTS"))
                .andExpect(jsonPath("$.message").value(
                        "This phone number has too many unpaid bookings. Pay for one of them, or try again later."))
                .andExpect(jsonPath("$.data.errorCode").value("TOO_MANY_UNPAID_BOOKINGS"))
                // The oldest was made just now, so it leaves the window in about 15 minutes.
                .andExpect(jsonPath("$.data.retryAfterSeconds", allOf(greaterThan(800), lessThanOrEqualTo(900))));

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM bookings WHERE phone_number = ?", Long.class, phone))
                .isEqualTo(5L);
        org.mockito.Mockito.verifyNoInteractions(seatServiceClient);
    }

    @Test
    void paidBookings_andOnesOlderThanTheWindow_neverCount() throws Exception {
        String phone = randomPhone();
        for (int i = 0; i < 5; i++) {
            seed(phone, Booking.BookingStatus.CONFIRMED);
        }
        for (int i = 0; i < 5; i++) {
            UUID old = seed(phone, Booking.BookingStatus.PENDING);
            jdbc.update("UPDATE bookings SET created_at = ? WHERE id = ?",
                    LocalDateTime.now(ZoneOffset.UTC).minusMinutes(20), old);
        }

        assertThat(bookingRepository.countUnpaidSince(phone, Booking.BookingStatus.CONFIRMED,
                LocalDateTime.now(ZoneOffset.UTC).minusMinutes(15))).isZero();
        // Past the cap, the request goes on to the category lookup.
        MvcResult result = book(phone).andReturn();
        assertThat(result.getResponse().getStatus()).isNotEqualTo(429);
    }

    // ---- helpers ----------------------------------------------------------------

    private org.springframework.test.web.servlet.ResultActions book(String phone) throws Exception {
        return mvc.perform(post("/bookings").contentType(MediaType.APPLICATION_JSON).content("""
                {"eventId":"%s","customerName":"Rufaro Moyo","phoneNumber":"%s",
                 "seats":[{"categoryId":"%s"}]}
                """.formatted(UUID.randomUUID(), phone, UUID.randomUUID())));
    }

    private static String randomPhone() {
        return "+26377" + (1_000_000 + (int) (Math.random() * 8_999_999));
    }

    private UUID seed(String phone, Booking.BookingStatus status) {
        return new TransactionTemplate(txManager).execute(s -> {
            Booking booking = Booking.builder()
                    .userEmail("buyer@example.com")
                    .phoneNumber(phone)
                    .customerName("Rufaro Moyo")
                    .eventId(UUID.randomUUID())
                    .confirmationNumber("INN-UP-" + UUID.randomUUID().toString().substring(0, 13).toUpperCase())
                    .status(status)
                    .totalAmount(new BigDecimal("10.00"))
                    .cashAmount(status == Booking.BookingStatus.CONFIRMED ? new BigDecimal("10.00") : null)
                    .expiresAt(status == Booking.BookingStatus.PENDING
                            ? LocalDateTime.now(ZoneOffset.UTC).plusMinutes(10) : null)
                    .cancelReason(status == Booking.BookingStatus.CANCELLED
                            ? Booking.CancelReason.CUSTOMER_CANCELLED : null)
                    .build();
            bookingRepository.save(booking);
            seeded.add(booking.getId());
            return booking.getId();
        });
    }
}
