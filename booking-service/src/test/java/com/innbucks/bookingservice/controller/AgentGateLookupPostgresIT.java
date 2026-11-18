package com.innbucks.bookingservice.controller;

import com.innbucks.bookingservice.dto.ApiResult;
import com.innbucks.bookingservice.dto.EventSalesWindowDTO;
import com.innbucks.bookingservice.dto.ScannerAccessDTO;
import com.innbucks.bookingservice.dto.SellerAccessDTO;
import com.innbucks.bookingservice.testsupport.BookingStockPostgresTestBase;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.innbucks.bookingservice.controller.TicketScanController.EXAMPLE_EVENT_ID_REQUIRED;
import static com.innbucks.bookingservice.controller.TicketScanController.EXAMPLE_LOOKUP_SOLD_BY_YOU;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A sales agent never holds an entry credential, not even when its team row
 * may also scan for the event (event-service V21 allows Scan and Sell on one
 * row). The agent holds the confirmation number of every sale it made (the
 * sale's answer, {@code my-sales}), and the gate lookup used to answer that
 * number with every ticket's whole number and item id: proven on the code
 * before this change, where the same session then admitted on one of them.
 *
 * <p>Through the real filter chain, real JWTs and Postgres: the agent's
 * lookup of its own sale is {@code SOLD_BY_YOU} from every session the
 * account signs in with (the Super app, the Organizer app's gate sign-in, its
 * own Foundry sign-in), compared strictly with the Swagger example, and
 * carries no credential; another scanner on the team and the organizer still
 * find it whole; and the agent still admits the QR the holder shows (owner's
 * decision, 2026-11-18: only the lookup refuses).
 */
class AgentGateLookupPostgresIT extends BookingStockPostgresTestBase {

    private final UUID eventId = UUID.randomUUID();
    private final UUID vip = UUID.randomUUID();
    private final UUID organizer = UUID.randomUUID();
    private final UUID agentRow = UUID.randomUUID();
    private final UUID agentAccount = UUID.randomUUID();
    private final UUID otherScannerRow = UUID.randomUUID();
    private final UUID otherScannerAccount = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        category(eventId, vip, "VIP", 100);
        when(eventServiceClient.salesWindow(eq(eventId), any())).thenReturn(ApiResult.ok("Sales window resolved",
                EventSalesWindowDTO.builder().eventId(eventId).status(EventSalesWindowDTO.ON_SALE)
                        .tenantUserUuid(organizer).build()));
        // The agent's one team row: Sell, and Scan, both assigned to this event.
        when(eventServiceClient.sellerAccess(any(), any(), any())).thenAnswer(call ->
                ApiResult.ok("Seller access", agentAccount.equals(call.getArgument(0))
                        && eventId.equals(call.getArgument(1))
                        ? SellerAccessDTO.builder().allowed(true).agentId(agentRow).organizerUuid(organizer)
                                .displayName("Tendai Moyo").commissionPercent(new BigDecimal("5.00")).build()
                        : SellerAccessDTO.builder().allowed(false).build()));
        // Made once, read live: re-stubbing races the async listeners a sale starts.
        when(eventServiceClient.scannerAccess(any(), any(), any())).thenAnswer(call -> {
            UUID account = call.getArgument(0);
            boolean thisEvent = eventId.equals(call.getArgument(1));
            if (thisEvent && agentAccount.equals(account)) {
                return ApiResult.ok("Scanner access resolved", ScannerAccessDTO.builder().allowed(true)
                        .scannerId(agentRow).organizerUuid(organizer).displayName("Tendai Moyo").build());
            }
            if (thisEvent && otherScannerAccount.equals(account)) {
                return ApiResult.ok("Scanner access resolved", ScannerAccessDTO.builder().allowed(true)
                        .scannerId(otherScannerRow).organizerUuid(organizer).displayName("Rudo Sibanda").build());
            }
            return ApiResult.ok("Scanner access resolved", ScannerAccessDTO.builder().allowed(false).build());
        });
    }

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM scan_attempts WHERE event_id = ?", eventId);
        jdbc.update("DELETE FROM booking_items WHERE booking_id IN (SELECT id FROM bookings WHERE event_id = ?)",
                eventId);
        jdbc.update("DELETE FROM bookings WHERE event_id = ?", eventId);
        deleteSeeded();
    }

    // ------------------------------------------------------------------ sessions

    /** The agent in the InnBucks app: CUSTOMER, its account, no email (user-service's phone-proof mint). */
    private String agentInTheSuperApp() {
        return bearer("+263772000111", List.of("CUSTOMER"),
                Map.of("userUuid", agentAccount.toString(), "phoneNumber", "+263772000111"));
    }

    /** The same account through the Organizer app's SMS-code gate sign-in: GATE_SCANNER alone. */
    private String agentInTheOrganizerApp() {
        return bearer("+263772000111", List.of("GATE_SCANNER"),
                Map.of("userUuid", agentAccount.toString(), "phoneNumber", "+263772000111"));
    }

    /** The same account's own Foundry sign-in (email, password, 2FA). */
    private String agentInFoundry() {
        return bearer("tendai.moyo@example.com", List.of("CUSTOMER", "SALES_AGENT"),
                Map.of("userUuid", agentAccount.toString(), "phoneNumber", "+263772000111"));
    }

    private String otherScanner() {
        return bearer("+263772000222", List.of("CUSTOMER"),
                Map.of("userUuid", otherScannerAccount.toString(), "phoneNumber", "+263772000222"));
    }

    private String organizerBearer() {
        return bearer("organizer@example.com", List.of("EVENT_ORGANIZER"),
                Map.of("organizerUuid", organizer.toString(), "userUuid", organizer.toString()));
    }

    // ------------------------------------------------------------------ calls

    /** A two-ticket box-office sale by the agent; answers its confirmation number. */
    private String sell(String paymentMethod) throws Exception {
        MvcResult sale = mvc.perform(post("/bookings/box-office").header("Authorization", agentInTheSuperApp())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"eventId":"%s","customerName":"Rufaro Moyo","phoneNumber":"+263772004567",
                                 "paymentMethod":"%s","seats":[{"categoryId":"%s"},{"categoryId":"%s"}]}"""
                                .formatted(eventId, paymentMethod, vip, vip)))
                .andReturn();
        String body = sale.getResponse().getContentAsString();
        assertThat(sale.getResponse().getStatus()).as(body).isEqualTo(201);
        return JsonPath.read(body, "$.data.confirmationNumber");
    }

    private ResultActions lookup(String bearer, String confirmation) throws Exception {
        return mvc.perform(post("/tickets/lookup").header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"confirmationNumber\":\"%s\",\"eventId\":\"%s\"}".formatted(confirmation, eventId)));
    }

    private List<String> issuedNumbers(String confirmation) {
        return jdbc.queryForList("""
                SELECT i.ticket_number FROM booking_items i JOIN bookings b ON b.id = i.booking_id
                WHERE b.confirmation_number = ? ORDER BY i.ticket_number""", String.class, confirmation);
    }

    private List<UUID> itemIds(String confirmation) {
        return jdbc.queryForList("""
                SELECT i.id FROM booking_items i JOIN bookings b ON b.id = i.booking_id
                WHERE b.confirmation_number = ?""", UUID.class, confirmation);
    }

    /** The lookup's answer holds nothing that admits or names: no ticket number, item id or buyer. */
    private void carriesNoCredential(String body, String confirmation) {
        for (String number : issuedNumbers(confirmation)) {
            assertThat(body).doesNotContain(number);
        }
        for (UUID item : itemIds(confirmation)) {
            assertThat(body).doesNotContain(item.toString());
        }
        assertThat(body).doesNotContain("Rufaro").doesNotContain("****4567").doesNotContain("tickets");
    }

    // ------------------------------------------------------------------ tests

    @Test
    void anAgentWhoAlsoScans_getsSoldByYou_forItsOwnCashSale_fromEverySignIn() throws Exception {
        String confirmation = sell("CASH");
        String expected = EXAMPLE_LOOKUP_SOLD_BY_YOU.replace("INN-20260901-3C8849", confirmation);

        for (String session : List.of(agentInTheSuperApp(), agentInTheOrganizerApp(), agentInFoundry())) {
            String body = lookup(session, confirmation)
                    .andExpect(status().isOk())
                    .andExpect(content().json(expected, JsonCompareMode.STRICT))
                    .andReturn().getResponse().getContentAsString();
            carriesNoCredential(body, confirmation);
        }
        // Read-only, like every lookup: nothing written to the scan audit.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM scan_attempts WHERE event_id = ?",
                Integer.class, eventId)).isZero();

        // A gate session must name the event: refused before anything is asked or read.
        mvc.perform(post("/tickets/lookup").header("Authorization", agentInTheOrganizerApp())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"confirmationNumber\":\"%s\"}".formatted(confirmation)))
                .andExpect(status().isBadRequest())
                .andExpect(content().json(EXAMPLE_EVENT_ID_REQUIRED, JsonCompareMode.STRICT));
    }

    @Test
    void theRestOfTheGate_stillFindsTheAgentsSaleWhole_andTheHoldersQrStillScansForTheAgent() throws Exception {
        String confirmation = sell("CASH");
        List<String> issued = issuedNumbers(confirmation);
        assertThat(issued).hasSize(2);

        // Another scanner on the team: learns the number only from the holder in front of them.
        String other = lookup(otherScanner(), confirmation)
                .andExpect(jsonPath("$.data.status").value("FOUND"))
                .andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<String>>read(other, "$.data.tickets[*].ticketNumber")).containsExactlyElementsOf(issued);

        // The organizer's own lookup is unchanged.
        String organizers = lookup(organizerBearer(), confirmation)
                .andExpect(jsonPath("$.data.status").value("FOUND"))
                .andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<String>>read(organizers, "$.data.tickets[*].ticketNumber"))
                .containsExactlyElementsOf(issued);

        // The holder shows the agent their QR: it scans (only the lookup refuses).
        mvc.perform(post("/tickets/scan").header("Authorization", agentInTheSuperApp())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ticketNumber\":\"%s\",\"eventId\":\"%s\"}".formatted(issued.get(0), eventId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ALLOWED"))
                .andExpect(jsonPath("$.data.redeemedByName").value("Tendai Moyo"));
    }

    @Test
    void anOnlineSale_isUnpaidUntilPaid_andThenSoldByYou() throws Exception {
        String confirmation = sell("ONLINE");
        lookup(agentInTheSuperApp(), confirmation)
                .andExpect(jsonPath("$.data.status").value("BOOKING_NOT_CONFIRMED"));

        UUID booking = jdbc.queryForObject("SELECT id FROM bookings WHERE confirmation_number = ?",
                UUID.class, confirmation);
        mvc.perform(patch("/bookings/internal/" + booking + "/confirm").header("X-Internal-Token", internalToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"paymentRail\":\"ECOCASH\"}"))
                .andExpect(status().isOk());

        String body = lookup(agentInTheSuperApp(), confirmation)
                .andExpect(content().json(EXAMPLE_LOOKUP_SOLD_BY_YOU.replace("INN-20260901-3C8849", confirmation),
                        JsonCompareMode.STRICT))
                .andReturn().getResponse().getContentAsString();
        carriesNoCredential(body, confirmation);
    }
}
