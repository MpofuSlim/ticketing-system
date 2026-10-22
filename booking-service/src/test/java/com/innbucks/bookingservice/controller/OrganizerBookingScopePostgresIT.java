package com.innbucks.bookingservice.controller;

import com.innbucks.bookingservice.entity.Booking;
import com.innbucks.bookingservice.entity.BookingItem;
import com.innbucks.bookingservice.testsupport.BookingStockPostgresTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * An EVENT_ORGANIZER reads {@code GET /bookings/phone/{phone}} and
 * {@code GET /bookings/confirmation/{number}} only for the events they own.
 * Before this, any organizer token was treated like platform staff on both, so
 * one organizer could read every other organizer's customers' bookings and
 * scannable QR codes by phone number or confirmation number.
 *
 * <p>Through the real filter chain with real signed JWTs, against Postgres:
 * ownership is the booking's own {@code tenantUserUuid}, so no event-service
 * call is involved.
 */
class OrganizerBookingScopePostgresIT extends BookingStockPostgresTestBase {

    private final UUID organizerA = UUID.randomUUID();
    private final UUID organizerB = UUID.randomUUID();
    private final String customerPhone = "+26377" + (1_000_000 + (int) (Math.random() * 8_999_999));
    private final List<UUID> seeded = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        for (UUID id : seeded) {
            jdbc.update("DELETE FROM booking_items WHERE booking_id = ?", id);
            jdbc.update("DELETE FROM bookings WHERE id = ?", id);
        }
    }

    @Test
    void phoneLookup_organizerSeesOnlyTheirOwnEventsBookings() throws Exception {
        Booking atA = seed(organizerA, customerPhone);
        seed(organizerB, customerPhone);

        mvc.perform(get("/bookings/phone/" + customerPhone)
                        .header("Authorization", organizer(organizerA, "+263719999999")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].confirmationNumber").value(atA.getConfirmationNumber()))
                .andExpect(jsonPath("$.data[0].items[0].qrCode").isNotEmpty());
    }

    @Test
    void phoneLookup_platformStaffAndTheCustomerSeeEveryBooking() throws Exception {
        Booking atA = seed(organizerA, customerPhone);
        Booking atB = seed(organizerB, customerPhone);

        mvc.perform(get("/bookings/phone/" + customerPhone)
                        .header("Authorization", bearer("admin@innbucks.co.zw", List.of("SUPER_ADMIN"), Map.of())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].confirmationNumber",
                        containsInAnyOrder(atA.getConfirmationNumber(), atB.getConfirmationNumber())));

        mvc.perform(get("/bookings/phone/" + customerPhone)
                        .header("Authorization", bearer("buyer@example.com", List.of("CUSTOMER"),
                                Map.of("phoneNumber", customerPhone))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(2)));
    }

    @Test
    void phoneLookup_anOrganizerReadingTheirOwnNumberSeesAllTheirOwnTickets() throws Exception {
        // Organizer A also bought a ticket to organizer B's event, as a customer.
        Booking ownTicketElsewhere = seed(organizerB, customerPhone);

        mvc.perform(get("/bookings/phone/" + customerPhone)
                        .header("Authorization", organizer(organizerA, customerPhone)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].confirmationNumber").value(ownTicketElsewhere.getConfirmationNumber()));
    }

    @Test
    void phoneLookup_anOrganizerTokenWithNoOrganizerId_is403() throws Exception {
        seed(organizerA, customerPhone);

        mvc.perform(get("/bookings/phone/" + customerPhone)
                        .header("Authorization", bearer("organizer@example.com", List.of("EVENT_ORGANIZER"), Map.of())))
                .andExpect(status().isForbidden());
    }

    @Test
    void confirmationLookup_organizerOfAnotherEventGets404_likeAnyNonOwner() throws Exception {
        Booking atA = seed(organizerA, customerPhone);
        Booking atB = seed(organizerB, customerPhone);

        mvc.perform(get("/bookings/confirmation/" + atA.getConfirmationNumber())
                        .header("Authorization", organizer(organizerA, "+263719999999")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].qrCode").isNotEmpty());

        mvc.perform(get("/bookings/confirmation/" + atB.getConfirmationNumber())
                        .header("Authorization", organizer(organizerA, "+263719999999")))
                .andExpect(status().isNotFound());

        mvc.perform(get("/bookings/confirmation/" + atB.getConfirmationNumber())
                        .header("Authorization", bearer("admin@innbucks.co.zw", List.of("PRODUCT_OFFICER"), Map.of())))
                .andExpect(status().isOk());
    }

    private String organizer(UUID organizerUuid, String phone) {
        return bearer("organizer-" + organizerUuid + "@example.com", List.of("EVENT_ORGANIZER"),
                Map.of("organizerUuid", organizerUuid.toString(), "phoneNumber", phone));
    }

    /** A CONFIRMED one-ticket booking of an event owned by {@code owner}, committed. */
    private Booking seed(UUID owner, String phone) {
        return new TransactionTemplate(txManager).execute(s -> {
            Booking booking = Booking.builder()
                    .userEmail("buyer-" + UUID.randomUUID() + "@example.com")
                    .phoneNumber(phone)
                    .customerName("Rufaro Moyo")
                    .eventId(UUID.randomUUID())
                    .tenantUserUuid(owner)
                    .confirmationNumber("INN-OS-" + UUID.randomUUID().toString().substring(0, 13).toUpperCase())
                    .status(Booking.BookingStatus.CONFIRMED)
                    .totalAmount(new BigDecimal("10.00"))
                    .cashAmount(new BigDecimal("10.00"))
                    .build();
            bookingRepository.save(booking);
            booking.setItems(List.of(bookingItemRepository.save(BookingItem.builder()
                    .booking(booking).seatId(UUID.randomUUID()).categoryId(UUID.randomUUID())
                    .rowLabel("GA").seatNumber(1).categoryName("General")
                    .priceAtBooking(new BigDecimal("10.00"))
                    .ticketNumber("OS-" + UUID.randomUUID())
                    .isActive(true)
                    .build())));
            seeded.add(booking.getId());
            return booking;
        });
    }
}
