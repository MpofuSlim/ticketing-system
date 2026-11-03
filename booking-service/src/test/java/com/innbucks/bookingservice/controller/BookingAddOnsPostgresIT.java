package com.innbucks.bookingservice.controller;

import com.innbucks.bookingservice.dto.ApiResult;
import com.innbucks.bookingservice.dto.CategoryLookupDTO;
import com.innbucks.bookingservice.dto.EventLookupDTO;
import com.innbucks.bookingservice.dto.EventSalesWindowDTO;
import com.innbucks.bookingservice.testsupport.BookingStockPostgresTestBase;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.innbucks.bookingservice.controller.BookingController.EXAMPLE_ADD_ONS_INVALID;
import static com.innbucks.bookingservice.controller.BookingController.EXAMPLE_ADD_ON_SOLD_OUT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Add-ons (V33) through the real filter chain, the app's JSON mapper and
 * Postgres: the jsonb count the limit is enforced from, the limit at its
 * boundary, a cancelled booking giving its add-ons back, the internal counts
 * seat-service reads, the guest list, the scan, the report, and both refusals
 * compared strictly with their Swagger examples.
 */
class BookingAddOnsPostgresIT extends BookingStockPostgresTestBase {

    private static final UUID PARKING = UUID.fromString("a1b2c3d4-0e1f-4a2b-8c3d-4e5f6a7b8c01");
    private static final UUID MEAL = UUID.fromString("a1b2c3d4-0e1f-4a2b-8c3d-4e5f6a7b8c02");

    private final UUID eventId = UUID.randomUUID();
    private final UUID vip = UUID.randomUUID();
    private final UUID ga = UUID.randomUUID();
    private final UUID organizer = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        category(eventId, vip, "VIP", 100);
        category(eventId, ga, "GA", 100);
        when(seatServiceClient.getCategory(vip)).thenReturn(ApiResult.ok(CategoryLookupDTO.builder()
                .seatCategoryId(vip).eventId(eventId).name("VIP").price(new BigDecimal("150.00")).totalSeats(100)
                .addOns(List.of(
                        CategoryLookupDTO.AddOn.builder().id(PARKING).name("Parking pass")
                                .price(new BigDecimal("5.00")).maxQuantity(2).build(),
                        CategoryLookupDTO.AddOn.builder().id(MEAL).name("Meal voucher")
                                .price(new BigDecimal("10.00")).build()))
                .build()));
        when(eventServiceClient.salesWindow(eq(eventId), any())).thenReturn(ApiResult.ok("Sales window resolved",
                EventSalesWindowDTO.builder().eventId(eventId).status(EventSalesWindowDTO.ON_SALE)
                        .tenantUserUuid(organizer).build()));
        when(eventServiceClient.getEventInternal(eq(eventId), any())).thenReturn(ApiResult.ok(
                EventLookupDTO.builder().eventId(eventId).tenantUserUuid(organizer).title("Jazz Night").build()));
    }

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM scan_attempts WHERE event_id = ?", eventId);
        jdbc.update("DELETE FROM booking_items WHERE booking_id IN (SELECT id FROM bookings WHERE event_id = ?)",
                eventId);
        jdbc.update("DELETE FROM bookings WHERE event_id = ?", eventId);
        deleteSeeded();
    }

    private ResultActions book(String seats) throws Exception {
        return mvc.perform(post("/bookings").contentType(MediaType.APPLICATION_JSON).content("""
                {"eventId":"%s","customerName":"Rufaro Moyo","phoneNumber":"+263771234567","seats":%s}
                """.formatted(eventId, seats)));
    }

    private String vipWith(UUID... addOns) {
        StringBuilder ids = new StringBuilder();
        for (UUID a : addOns) {
            ids.append(ids.isEmpty() ? "" : ",").append('"').append(a).append('"');
        }
        return "{\"categoryId\":\"" + vip + "\",\"addOnIds\":[" + ids + "]}";
    }

    private ResultActions counts() throws Exception {
        return mvc.perform(get("/bookings/internal/categories/add-on-counts")
                .param("categoryIds", vip.toString(), ga.toString()).header("X-Internal-Token", internalToken));
    }

    private String organizerBearer() {
        return bearer("organizer@example.com", List.of("EVENT_ORGANIZER"),
                Map.of("organizerUuid", organizer.toString()));
    }

    @Test
    void aLimitIsExact_aCancelledBookingGivesItsAddOnsBack_andSeatServiceReadsTheCount() throws Exception {
        String first = book("[" + vipWith(PARKING, MEAL) + ",{\"categoryId\":\"" + ga + "\"}]")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.totalAmount").value(175.00))
                .andExpect(jsonPath("$.data.addOnsAmount").value(15.00))
                .andExpect(jsonPath("$.data.items[0].priceAtBooking").value(150.00))
                .andExpect(jsonPath("$.data.items[0].addOns", hasSize(2)))
                .andExpect(jsonPath("$.data.items[0].addOns[0].name").value("Parking pass"))
                .andExpect(jsonPath("$.data.items[1].addOns", hasSize(0)))
                .andReturn().getResponse().getContentAsString();
        UUID firstId = UUID.fromString(JsonPath.read(first, "$.data.id"));
        assertThat(jdbc.queryForObject("SELECT add_ons FROM booking_items WHERE booking_id = ? AND category_id = ?",
                String.class, firstId, vip)).contains(PARKING.toString()).contains("Parking pass");

        counts().andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(2)))
                .andExpect(jsonPath("$.data[?(@.addOnId == '" + PARKING + "')].count").value(1))
                .andExpect(jsonPath("$.data[?(@.addOnId == '" + MEAL + "')].count").value(1));

        // 1 of 2 parking passes left: two more are one too many, and nothing is held.
        book("[" + vipWith(PARKING) + "," + vipWith(PARKING) + "]")
                .andExpect(status().isConflict())
                .andExpect(content().json(EXAMPLE_ADD_ON_SOLD_OUT, JsonCompareMode.STRICT));
        assertThat(remaining(vip)).isEqualTo(99);

        // The last one sells; the next is sold out.
        book("[" + vipWith(PARKING) + "]").andExpect(status().isCreated());
        book("[" + vipWith(PARKING) + "]")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("\"Parking pass\" is sold out."))
                .andExpect(jsonPath("$.data.remaining").value(0));

        // A ticket without the add-on still sells, and the meal voucher has no limit.
        book("[" + vipWith(MEAL) + "," + vipWith() + "]").andExpect(status().isCreated());

        // Cancelling the first booking gives its parking pass back.
        jdbc.update("UPDATE bookings SET status = 'CANCELLED', cancel_reason = 'CUSTOMER_CANCELLED' WHERE id = ?",
                firstId);
        counts().andExpect(jsonPath("$.data[?(@.addOnId == '" + PARKING + "')].count").value(1));
        book("[" + vipWith(PARKING) + "]").andExpect(status().isCreated());
    }

    @Test
    void theOrganizerSeesAddOnsOnTheGuestList_theGateOnTheScan_andTheReportCountsPaidOnes() throws Exception {
        String created = book("[" + vipWith(PARKING, MEAL) + "," + vipWith(MEAL) + "]")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String ticket = JsonPath.read(created, "$.data.items[0].ticketNumber");
        jdbc.update("UPDATE bookings SET status = 'CONFIRMED' WHERE event_id = ?", eventId);

        mvc.perform(get("/bookings/by-event/" + eventId).header("Authorization", organizerBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(2)))
                .andExpect(jsonPath("$.data[*].addOns[*].name", hasSize(3)));

        mvc.perform(post("/tickets/scan").header("Authorization", organizerBearer())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"ticketNumber\":\"" + ticket + "\"}"))
                .andExpect(jsonPath("$.data.status").value("ALLOWED"))
                .andExpect(jsonPath("$.data.addOns", hasSize(2)))
                .andExpect(jsonPath("$.data.addOns[0]").value("Parking pass"))
                .andExpect(jsonPath("$.data.addOns[1]").value("Meal voucher"));

        mvc.perform(get("/event-organizer/reports/add-ons").param("eventId", eventId.toString())
                        .header("Authorization", organizerBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(2)))
                .andExpect(jsonPath("$.data[0].name").value("Meal voucher"))
                .andExpect(jsonPath("$.data[0].sold").value(2))
                .andExpect(jsonPath("$.data[0].revenue").value(20.00))
                .andExpect(jsonPath("$.data[0].categoryName").value("VIP"))
                .andExpect(jsonPath("$.data[1].name").value("Parking pass"))
                .andExpect(jsonPath("$.data[1].sold").value(1))
                .andExpect(jsonPath("$.data[1].revenue").value(5.00))
                .andExpect(jsonPath("$.meta.eventId").value(eventId.toString()));
    }

    @Test
    void anAddOnNotSoldWithThatTicket_isTheDocumentedBody_andHoldsNothing() throws Exception {
        book("[" + vipWith(PARKING) + ",{\"categoryId\":\"" + ga + "\",\"addOnIds\":[\"" + PARKING + "\"]}]")
                .andExpect(status().isBadRequest())
                .andExpect(content().json(EXAMPLE_ADD_ONS_INVALID, JsonCompareMode.STRICT));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bookings WHERE event_id = ?", Integer.class, eventId))
                .isZero();
        assertThat(remaining(vip)).isEqualTo(100);
    }

    @Test
    void theCounts_needTheInternalToken() throws Exception {
        mvc.perform(get("/bookings/internal/categories/add-on-counts").param("categoryIds", vip.toString()))
                .andExpect(status().isUnauthorized());
    }
}
