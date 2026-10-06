package com.innbucks.bookingservice;

import com.innbucks.bookingservice.client.EmailNotificationClient;
import com.innbucks.bookingservice.client.EventServiceClient;
import com.innbucks.bookingservice.client.SeatServiceClient;
import com.innbucks.bookingservice.client.SmsNotificationClient;
import com.innbucks.bookingservice.client.UserServiceClient;
import com.innbucks.bookingservice.client.WhatsAppNotificationClient;
import com.innbucks.bookingservice.dto.ApiResult;
import com.innbucks.bookingservice.dto.AvailabilityResponseDTO;
import com.innbucks.bookingservice.dto.CategoryLookupDTO;
import com.innbucks.bookingservice.dto.EventLookupDTO;
import com.innbucks.bookingservice.entity.Booking;
import com.innbucks.bookingservice.entity.BookingItem;
import com.innbucks.bookingservice.repository.BookingItemRepository;
import com.innbucks.bookingservice.repository.BookingRepository;
import com.innbucks.bookingservice.security.JwtUtil;
import com.innbucks.bookingservice.service.BookingExpirationService;
import com.innbucks.bookingservice.service.EventReminderScheduler;
import com.innbucks.bookingservice.testsupport.PostgresIntegrationTestBase;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code Booking.items} and {@code BookingItem.booking} are LAZY and
 * {@code spring.jpa.open-in-view} is false, so every path that renders a
 * booking's tickets must either read them inside a transaction or fetch them
 * in its query. A path that does neither throws
 * {@code LazyInitializationException} — a 500 over HTTP, a silently dropped
 * delivery on the async listeners, an aborted tick on a scheduler.
 *
 * <p>Two things are pinned here against real Postgres, through the real HTTP
 * layer (MockMvc with the full filter chain and a real signed JWT):
 * <ol>
 *   <li><b>Every path that reads the associations works</b> — each endpoint
 *       renders its items, the confirm listener delivers every ticket, the
 *       expiry sweep and the reminder / event-change fan-outs run.</li>
 *   <li><b>The statement count of each path</b>, from Hibernate's
 *       {@link Statistics} ({@code hibernate.generate_statistics} is on in the
 *       {@code it} profile only). The list paths are measured at two sizes and
 *       must cost the same: their cost is independent of how many bookings
 *       come back. With the old EAGER mapping every list fanned out one items
 *       query per booking.</li>
 * </ol>
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        // Keep every background job out of the statistics window: these tests
        // drive the jobs by hand.
        "app.booking.reminder-cron=-",
        "app.booking.organizer-reminder-cron=-",
        "app.booking.expiration-poll-interval-ms=86400000",
        "app.loyalty-earn-retry.initial-delay-ms=86400000",
        "app.invoicing.scheduler-enabled=false",
        // The event-day rule asks event-service (mocked to answer nothing for
        // scans); it is not what is under test here.
        "innbucks.scan.event-day-check.enabled=false"
})
class LazyAssociationsPostgresIT extends PostgresIntegrationTestBase {

    @MockitoBean private WhatsAppNotificationClient whatsApp;
    @MockitoBean private EmailNotificationClient email;
    @MockitoBean private SmsNotificationClient sms;
    @MockitoBean private EventServiceClient eventServiceClient;
    @MockitoBean private SeatServiceClient seatServiceClient;
    @MockitoBean private UserServiceClient userServiceClient;

    @Autowired private MockMvc mvc;
    @Autowired private BookingRepository bookingRepository;
    @Autowired private BookingItemRepository bookingItemRepository;
    @Autowired private BookingExpirationService expirationService;
    @Autowired private EventReminderScheduler eventReminderScheduler;
    @Autowired private PlatformTransactionManager txManager;
    @Autowired private EntityManagerFactory emf;
    @Autowired private JdbcTemplate jdbc;

    @Value("${jwt.secret}")
    private String jwtSecret;

    @Value("${innbucks.internal-api-token}")
    private String internalToken;

    private TransactionTemplate tx;
    private Statistics stats;

    private final UUID organizer = UUID.randomUUID();

    private static final long EXPIRY_SWEEP_STATEMENTS = 13;

    /** Measured statement counts, printed at the end of each test for the record. */
    private final Map<String, Long> measured = new LinkedHashMap<>();

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(txManager);
        stats = emf.unwrap(SessionFactory.class).getStatistics();
        assertThat(stats.isStatisticsEnabled()).as("hibernate.generate_statistics in the it profile").isTrue();
        clean();
    }

    @AfterEach
    void tearDown() {
        measured.forEach((k, v) -> System.out.println("[query-count] " + k + " = " + v));
        clean();
    }

    private void clean() {
        jdbc.update("DELETE FROM scan_attempts");
        jdbc.update("DELETE FROM booking_items");
        jdbc.update("DELETE FROM bookings");
        jdbc.update("DELETE FROM category_inventory");
        // Expire, never delete: ShedLock remembers which lock rows it has
        // seen and only UPDATEs those, so a deleted row would never be taken
        // again and a job driven by hand would silently not run.
        jdbc.update("UPDATE shedlock SET lock_until = TIMESTAMP '2000-01-01 00:00:00'");
    }

    // ---- list paths: cost independent of N --------------------------------

    @Test
    void listEndpoints_renderEveryTicket_andCostTheSameForThreeOrTwelveBookings() throws Exception {
        Fixture small = seedEvent(3);
        Fixture large = seedEvent(12);

        for (Fixture f : List.of(small, large)) {
            String n = "[N=" + f.bookings.size() + "] ";
            when(eventServiceClient.getEventInternal(eq(f.eventId), anyString())).thenReturn(ApiResult.ok(
                    EventLookupDTO.builder().eventId(f.eventId).tenantUserUuid(organizer).title("Jazz Night")
                            .startDateTime(LocalDateTime.now(ZoneOffset.UTC).plusDays(3))
                            .endDateTime(LocalDateTime.now(ZoneOffset.UTC).plusDays(3).plusHours(4))
                            .build()));
            int size = f.bookings.size();

            measure(n + "GET /bookings/my", get("/bookings/my").header("Authorization", bearer(customer(f))),
                    r -> r.andExpect(jsonPath("$.data", hasSize(size)))
                            .andExpect(jsonPath("$.data[0].items", hasSize(2)))
                            .andExpect(jsonPath("$.data[*].items[*].attendeeEmail").isNotEmpty()));

            measure(n + "GET /bookings/phone/{phone}", get("/bookings/phone/" + f.phone)
                            .header("Authorization", bearer(customer(f))),
                    r -> r.andExpect(jsonPath("$.data", hasSize(size)))
                            .andExpect(jsonPath("$.data[" + (size - 1) + "].items", hasSize(2))));

            measure(n + "GET /bookings/public/phone/{phone}", get("/bookings/public/phone/" + f.phone),
                    r -> r.andExpect(jsonPath("$.data", hasSize(size)))
                            .andExpect(jsonPath("$.data[0].items", hasSize(2)))
                            .andExpect(jsonPath("$.data[0].items[*].attendeeName").isNotEmpty()));

            measure(n + "GET /bookings/by-event/{id} (guest list)", get("/bookings/by-event/" + f.eventId)
                            .header("Authorization", bearer(admin())),
                    r -> r.andExpect(jsonPath("$.data", hasSize(size * 2)))
                            .andExpect(jsonPath("$.data[*].holderName").isNotEmpty())
                            .andExpect(jsonPath("$.data[*].customerName").isNotEmpty()));

            measure(n + "GET /bookings/by-category/{id}", get("/bookings/by-category/" + f.categoryId)
                            .header("Authorization", bearer(admin())),
                    r -> r.andExpect(jsonPath("$.data", hasSize(size * 2))));

            measure(n + "GET /event-organizer/reports/revenue", get("/event-organizer/reports/revenue")
                            .param("eventId", f.eventId.toString()).header("Authorization", bearer(organizerToken())),
                    r -> r.andExpect(jsonPath("$.data.ticketsSold").value(size * 2)));

            measure(n + "GET /event-organizer/reports/by-event", get("/event-organizer/reports/by-event")
                            .header("Authorization", bearer(organizerToken())),
                    r -> r.andExpect(status().isOk()));

            measure(n + "GET /event-organizer/reports/by-category", get("/event-organizer/reports/by-category")
                            .param("eventId", f.eventId.toString()).header("Authorization", bearer(organizerToken())),
                    r -> r.andExpect(jsonPath("$.data[0].ticketsSold").value(size * 2)));

            measure(n + "GET /event-organizer/reports/time-series", get("/event-organizer/reports/time-series")
                            .param("eventId", f.eventId.toString()).header("Authorization", bearer(organizerToken())),
                    r -> r.andExpect(jsonPath("$.data[0].ticketsSold").value(size * 2)));

            String csv = measure(n + "GET /event-organizer/reports/bookings/export",
                    get("/event-organizer/reports/bookings/export")
                            .param("eventId", f.eventId.toString()).header("Authorization", bearer(organizerToken())),
                    r -> r.andExpect(status().isOk()));
            assertThat(csv).contains("Guest One");
        }

        // The list paths cost the same however many bookings they return.
        for (String endpoint : List.of("GET /bookings/my", "GET /bookings/phone/{phone}",
                "GET /bookings/public/phone/{phone}", "GET /bookings/by-event/{id} (guest list)",
                "GET /bookings/by-category/{id}", "GET /event-organizer/reports/revenue",
                "GET /event-organizer/reports/by-event", "GET /event-organizer/reports/by-category",
                "GET /event-organizer/reports/time-series", "GET /event-organizer/reports/bookings/export")) {
            assertThat(measured.get("[N=12] " + endpoint))
                    .as("statements for %s at N=12 vs N=3", endpoint)
                    .isEqualTo(measured.get("[N=3] " + endpoint));
        }
        assertThat(measured.get("[N=12] GET /bookings/my")).isEqualTo(2);
        assertThat(measured.get("[N=12] GET /bookings/phone/{phone}")).isEqualTo(2);
        assertThat(measured.get("[N=12] GET /bookings/public/phone/{phone}")).isEqualTo(2);
        assertThat(measured.get("[N=12] GET /bookings/by-event/{id} (guest list)")).isEqualTo(1);
        assertThat(measured.get("[N=12] GET /bookings/by-category/{id}")).isEqualTo(1);
        assertThat(measured.get("[N=12] GET /event-organizer/reports/revenue")).isEqualTo(2);
        assertThat(measured.get("[N=12] GET /event-organizer/reports/bookings/export")).isEqualTo(1);
    }

    // ---- single-booking paths ---------------------------------------------

    @Test
    void singleBookingEndpoints_renderTheirTickets_withTheBookingInOneStatement() throws Exception {
        Fixture f = seedEvent(1);
        Booking b = f.bookings.get(0);
        String ownTicket = f.ticketNumbers.get(0);
        String guestTicket = f.ticketNumbers.get(1);

        measure("GET /bookings/{id}", get("/bookings/" + b.getId()).header("Authorization", bearer(customer(f))),
                r -> r.andExpect(jsonPath("$.data.items", hasSize(2)))
                        .andExpect(jsonPath("$.data.items[*].qrCode").isNotEmpty()));
        measure("GET /bookings/public/{id}", get("/bookings/public/" + b.getId()),
                r -> r.andExpect(jsonPath("$.data.items", hasSize(2))));
        measure("GET /bookings/confirmation/{n}", get("/bookings/confirmation/" + b.getConfirmationNumber())
                        .header("Authorization", bearer(customer(f))),
                r -> r.andExpect(jsonPath("$.data.items", hasSize(2))));
        measure("GET /bookings/internal/{id}", get("/bookings/internal/" + b.getId())
                        .header("X-Internal-Token", internalToken),
                r -> r.andExpect(jsonPath("$.data.items", hasSize(2))));
        measure("GET /bookings/{id}/tickets (HTML)", get("/bookings/" + b.getId() + "/tickets"),
                r -> r.andExpect(status().isOk()));
        measure("GET /bookings/{id}/tickets/{n}/qr", get("/bookings/" + b.getId() + "/tickets/" + ownTicket + "/qr"),
                r -> r.andExpect(status().isOk()));
        measure("POST /tickets/lookup", post("/tickets/lookup").header("Authorization", bearer(organizerToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"confirmationNumber\":\"" + b.getConfirmationNumber() + "\"}"),
                r -> r.andExpect(jsonPath("$.data.status").value("FOUND"))
                        .andExpect(jsonPath("$.data.tickets", hasSize(2)))
                        .andExpect(jsonPath("$.data.tickets[*].holderName").isNotEmpty()));
        measure("POST /tickets/scan", post("/tickets/scan").header("Authorization", bearer(organizerToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ticketNumber\":\"" + guestTicket + "\"}"),
                r -> r.andExpect(jsonPath("$.data.status").value("ALLOWED"))
                        .andExpect(jsonPath("$.data.holderName").value("Guest One")));

        assertThat(measured.get("GET /bookings/{id}")).isEqualTo(1);
        assertThat(measured.get("GET /bookings/public/{id}")).isEqualTo(1);
        assertThat(measured.get("GET /bookings/confirmation/{n}")).isEqualTo(1);
        assertThat(measured.get("GET /bookings/internal/{id}")).isEqualTo(1);
        assertThat(measured.get("GET /bookings/{id}/tickets (HTML)")).isEqualTo(1);
        assertThat(measured.get("GET /bookings/{id}/tickets/{n}/qr")).isEqualTo(1);
        assertThat(measured.get("POST /tickets/lookup")).isEqualTo(1);
        // select item + booking, claim UPDATE, scan_attempts INSERT — the
        // booking's tickets are no longer loaded behind the scan's back.
        assertThat(measured.get("POST /tickets/scan")).isEqualTo(3);
    }

    // ---- write paths + the async listeners they trigger -------------------

    @Test
    void confirm_thenAsyncDelivery_sendsEveryTicket() throws Exception {
        Fixture f = seedEvent(1, Booking.BookingStatus.PENDING);
        Booking b = f.bookings.get(0);

        stats.clear();
        mvc.perform(patch("/bookings/internal/" + b.getId() + "/confirm").header("X-Internal-Token", internalToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.data.items", hasSize(2)));
        // Ticket 1 is the purchaser's (QR to their WhatsApp); ticket 2's
        // attendee has their own phone, so its QR goes to them. Both sends
        // prove the listener read the items after commit, off any session.
        verify(whatsApp, timeout(10_000)).sendEventQrCode(eq(f.phone), anyString(), anyString());
        verify(whatsApp, timeout(10_000)).sendEventQrCode(eq("+263772000001"), anyString(), anyString());
        verify(email, timeout(10_000)).sendEmail(eq(f.email), anyString(), anyString(), anyString());
        measured.put("confirm + async delivery", stats.getPrepareStatementCount());
        // select booking + items, update booking, and the listener's one
        // fetch-join read after commit.
        assertThat(stats.getPrepareStatementCount()).isEqualTo(3);

        stats.clear();
        mvc.perform(post("/bookings/" + b.getId() + "/resend-ticket").header("Authorization", bearer(admin())))
                .andExpect(status().isOk());
        measured.put("POST /bookings/{id}/resend-ticket", stats.getPrepareStatementCount());
        assertThat(stats.getPrepareStatementCount()).isEqualTo(1);
    }

    @Test
    void cancel_reverse_extendHold_renderTheirTickets() throws Exception {
        Fixture pending = seedEvent(1, Booking.BookingStatus.PENDING);
        Booking p = pending.bookings.get(0);

        measure("PATCH /bookings/internal/{id}/extend-hold", patch("/bookings/internal/" + p.getId() + "/extend-hold")
                        .header("X-Internal-Token", internalToken).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"holdUntil\":\"" + LocalDateTime.now(ZoneOffset.UTC).plusMinutes(20) + "\"}"),
                r -> r.andExpect(jsonPath("$.data.items", hasSize(2))));

        stats.clear();
        mvc.perform(patch("/bookings/" + p.getId() + "/cancel").header("Authorization", bearer(customer(pending))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELLED"))
                .andExpect(jsonPath("$.data.items", hasSize(2)));
        // The cancel notice is sent by the async listener after commit; its
        // read is part of what a cancel costs.
        verify(email, timeout(10_000)).sendEmail(eq(pending.email), anyString(), anyString(), anyString());
        measured.put("PATCH /bookings/{id}/cancel + async notice", stats.getPrepareStatementCount());
        assertThat(inventoryReleased(pending.categoryId)).isEqualTo(2);

        Fixture confirmed = seedEvent(1);
        Booking c = confirmed.bookings.get(0);
        when(eventServiceClient.releaseAvailability(eq(confirmed.eventId), anyInt(), anyString()))
                .thenReturn(ApiResult.ok(released(10)));
        measure("PATCH /bookings/{id}/reverse", patch("/bookings/" + c.getId() + "/reverse")
                        .header("Authorization", bearer(admin())),
                r -> r.andExpect(jsonPath("$.data.status").value("CANCELLED"))
                        .andExpect(jsonPath("$.data.items", hasSize(2))));
        verify(eventServiceClient).releaseAvailability(confirmed.eventId, 2, internalToken);

        assertThat(measured.get("PATCH /bookings/internal/{id}/extend-hold")).isEqualTo(2);
        // select + items, update booking, release the counter, the listener's read.
        assertThat(measured.get("PATCH /bookings/{id}/cancel + async notice")).isEqualTo(4);
        // The reverse reads the booking + items TWICE: once in a read-only
        // transaction before the event-service release (so no connection is
        // held across it), and again in the write transaction after it, where
        // its version is compared with the first read. The rest is as for the
        // cancel: update booking, release the counter, the listener's read.
        assertThat(measured.get("PATCH /bookings/{id}/reverse")).isEqualTo(5);
    }

    @Test
    void createBooking_rendersTheTicketsItJustIssued() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID categoryId = UUID.randomUUID();
        when(seatServiceClient.getCategory(categoryId)).thenReturn(ApiResult.ok(CategoryLookupDTO.builder()
                .seatCategoryId(categoryId).eventId(eventId).name("General").price(new BigDecimal("10.00"))
                .totalSeats(100).build()));
        when(eventServiceClient.getEventInternal(eq(eventId), anyString())).thenReturn(ApiResult.ok(
                EventLookupDTO.builder().eventId(eventId).tenantUserUuid(organizer).build()));

        measure("POST /bookings", post("/bookings").contentType(MediaType.APPLICATION_JSON).content("""
                        {"eventId":"%s","customerName":"Rufaro Moyo","phoneNumber":"+263771234567",
                         "seats":[{"categoryId":"%s"},
                                  {"categoryId":"%s","attendee":{"fullName":"Guest One"}}]}
                        """.formatted(eventId, categoryId, categoryId)),
                r -> r.andExpect(status().isCreated())
                        .andExpect(jsonPath("$.data.items", hasSize(2)))
                        .andExpect(jsonPath("$.data.items[1].attendeeName").value("Guest One")));
    }

    // ---- schedulers and the event-change fan-out ----------------------------

    @Test
    void expirySweep_releasesEveryExpiredHold_inABoundedNumberOfStatements() {
        Fixture f = seedEvent(5, Booking.BookingStatus.PENDING);
        jdbc.update("UPDATE bookings SET expires_at = ? WHERE event_id = ?",
                LocalDateTime.now(ZoneOffset.UTC).minusMinutes(1), f.eventId);

        stats.clear();
        expirationService.expirePending();

        assertThat(jdbc.queryForObject("SELECT count(*) FROM bookings WHERE status = 'CANCELLED' AND event_id = ?",
                Long.class, f.eventId)).isEqualTo(5);
        assertThat(inventoryReleased(f.categoryId)).isEqualTo(10);
        // Each cancellation notice is sent after commit by the async listener,
        // which reads the booking off any session. Counted once they are all
        // out, so the figure does not depend on how far they had got.
        verify(email, timeout(10_000).times(5)).sendEmail(eq(f.email), anyString(), anyString(), anyString());
        long sweep = stats.getPrepareStatementCount();
        measured.put("expirePending (5 holds, 10 tickets) + 5 async notices", sweep);
        // The writes (status UPDATE, counter release) and the listeners' one
        // booking read each are per hold by nature; the sweep's own reads are
        // not: one SELECT of the holds and ONE batched items query for all
        // five, where EAGER loaded each hold's items separately.
        assertThat(sweep).isEqualTo(EXPIRY_SWEEP_STATEMENTS);
    }

    @Test
    void reminders_andEventChangeBroadcast_readOnlyTheBookingRow() throws Exception {
        Fixture f = seedEvent(4);
        when(eventServiceClient.getEvent(f.eventId)).thenReturn(ApiResult.ok(EventLookupDTO.builder()
                .eventId(f.eventId).title("Harare Jazz Night")
                .startDateTime(LocalDateTime.now(ZoneOffset.UTC).plusHours(5)).build()));

        stats.clear();
        eventReminderScheduler.remind();
        measured.put("EventReminderScheduler.remind (4 bookings)", stats.getPrepareStatementCount());
        verify(sms, times(4)).sendSms(eq(f.phone), anyString(), anyString());
        // Two event-id scans, then per event and stage one SELECT + one
        // batched UPDATE. No items are read.
        assertThat(stats.getPrepareStatementCount()).isEqualTo(6);

        stats.clear();
        mvc.perform(post("/bookings/internal/events/" + f.eventId + "/change-notification")
                        .header("X-Internal-Token", internalToken).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"changeType\":\"UPDATED\",\"eventTitle\":\"Harare Jazz Night\",\"newVenue\":\"HICC\"}"))
                .andExpect(status().isAccepted());
        verify(sms, timeout(10_000).times(8)).sendSms(eq(f.phone), anyString(), anyString());
        measured.put("event-change broadcast (4 bookings)", stats.getPrepareStatementCount());
        // One SELECT of the bookings and nothing per booking.
        assertThat(stats.getPrepareStatementCount()).isEqualTo(1);
    }

    // ---- helpers -------------------------------------------------------------

    @FunctionalInterface
    private interface Expectations {
        void apply(ResultActions r) throws Exception;
    }

    private String measure(String name, MockHttpServletRequestBuilder request, Expectations expectations)
            throws Exception {
        stats.clear();
        var actions = mvc.perform(request);
        long count = stats.getPrepareStatementCount();
        MvcResult result = actions.andReturn();
        assertThat(result.getResponse().getStatus())
                .as("%s -> %s", name, result.getResponse().getContentAsString())
                .isLessThan(400);
        expectations.apply(actions);
        measured.put(name, count);
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private static AvailabilityResponseDTO released(int remaining) {
        AvailabilityResponseDTO dto = new AvailabilityResponseDTO();
        dto.setAvailableTickets(remaining);
        return dto;
    }

    private long inventoryReleased(UUID categoryId) {
        // Seeded at 0 remaining, so whatever is there now was released.
        return jdbc.queryForObject("SELECT remaining FROM category_inventory WHERE category_id = ?",
                Long.class, categoryId);
    }

    private record Fixture(UUID eventId, UUID categoryId, String phone, String email,
                           List<Booking> bookings, List<String> ticketNumbers) { }

    private Fixture seedEvent(int bookings) {
        return seedEvent(bookings, Booking.BookingStatus.CONFIRMED);
    }

    /**
     * {@code bookings} bookings for one event, one purchaser, one category,
     * two tickets each: the purchaser's own, and one for a named guest with
     * their own contact details.
     */
    private Fixture seedEvent(int bookings, Booking.BookingStatus status) {
        UUID eventId = UUID.randomUUID();
        UUID categoryId = UUID.randomUUID();
        String phone = "+26377" + String.format("%07d", (int) (Math.random() * 9_000_000) + 1_000_000);
        String emailAddr = "buyer-" + eventId + "@example.com";
        List<Booking> saved = new ArrayList<>();
        List<String> tickets = new ArrayList<>();
        tx.executeWithoutResult(s -> {
            jdbc.update("INSERT INTO category_inventory (category_id, remaining) VALUES (?, 0)", categoryId);
            for (int i = 0; i < bookings; i++) {
                Booking booking = Booking.builder()
                        .userEmail(emailAddr)
                        .phoneNumber(phone)
                        .customerName("Rufaro Moyo")
                        .eventId(eventId)
                        .tenantUserUuid(organizer)
                        .confirmationNumber("INN-LZ-" + UUID.randomUUID().toString().substring(0, 13).toUpperCase())
                        .status(status)
                        .totalAmount(new BigDecimal("20.00"))
                        .cashAmount(status == Booking.BookingStatus.CONFIRMED ? new BigDecimal("20.00") : null)
                        .expiresAt(status == Booking.BookingStatus.PENDING
                                ? LocalDateTime.now(ZoneOffset.UTC).plusMinutes(10) : null)
                        .build();
                bookingRepository.save(booking);
                List<BookingItem> items = new ArrayList<>();
                for (int t = 1; t <= 2; t++) {
                    String ticketNumber = "LZ-" + UUID.randomUUID();
                    BookingItem.BookingItemBuilder item = BookingItem.builder()
                            .booking(booking)
                            .seatId(UUID.randomUUID())
                            .categoryId(categoryId)
                            .rowLabel("GA")
                            .seatNumber(t)
                            .categoryName("General")
                            .priceAtBooking(new BigDecimal("10.00"))
                            .ticketNumber(ticketNumber)
                            .isActive(true);
                    if (t == 2) {
                        item.attendeeName("Guest One").attendeePhone("+263772000001")
                                .attendeeEmail("guest@example.com");
                    }
                    items.add(bookingItemRepository.save(item.build()));
                    tickets.add(ticketNumber);
                }
                booking.setItems(items);
                saved.add(booking);
            }
        });
        return new Fixture(eventId, categoryId, phone, emailAddr, saved, tickets);
    }

    private Map<String, Object> customer(Fixture f) {
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("sub", f.email);
        claims.put("roles", List.of("CUSTOMER"));
        claims.put("phoneNumber", f.phone);
        return claims;
    }

    private Map<String, Object> admin() {
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("sub", "admin@innbucks.co.zw");
        claims.put("roles", List.of("SUPER_ADMIN"));
        return claims;
    }

    private Map<String, Object> organizerToken() {
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("sub", "organizer@example.com");
        claims.put("roles", List.of("EVENT_ORGANIZER"));
        claims.put("organizerUuid", organizer.toString());
        return claims;
    }

    private String bearer(Map<String, Object> claims) {
        Map<String, Object> rest = new LinkedHashMap<>(claims);
        String subject = (String) rest.remove("sub");
        return "Bearer " + Jwts.builder()
                .subject(subject)
                .issuer(JwtUtil.TOKEN_ISSUER)
                .audience().add(JwtUtil.TOKEN_AUDIENCE).and()
                .claims(rest)
                .issuedAt(Date.from(Instant.now()))
                .expiration(Date.from(Instant.now().plusSeconds(600)))
                .signWith(Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8)))
                .compact();
    }
}
