package com.innbucks.seatservice;

import com.innbucks.seatservice.client.BookingServiceClient;
import com.innbucks.seatservice.client.EventServiceClient;
import com.innbucks.seatservice.entity.Seat;
import com.innbucks.seatservice.entity.SeatCategory;
import com.innbucks.seatservice.repository.SeatCategoryRepository;
import com.innbucks.seatservice.repository.SeatRepository;
import com.innbucks.seatservice.security.JwtUtil;
import com.innbucks.seatservice.service.SeatService;
import com.innbucks.seatservice.testsupport.PostgresIntegrationTestBase;
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
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/**
 * {@code Seat.category} is LAZY and {@code spring.jpa.open-in-view} is false,
 * so every path that reads a seat's category must fetch it in its query or
 * read it inside a transaction — otherwise it throws
 * {@code LazyInitializationException} (a 500 here). Drives every such path
 * through the real HTTP layer against real Postgres, and pins its statement
 * count from Hibernate's {@link Statistics} ({@code hibernate.generate_statistics}
 * is on in the {@code it} profile only).
 *
 * <p>The category listing is the one public {@code GET /events/{id}} fans out
 * to. It used to load every seat row of the event to count sections (plus one
 * category query per category, through the EAGER mapping); it now asks
 * Postgres for the per-section counts. Its response — section order included —
 * is unchanged, and its cost no longer depends on how many categories or seats
 * the event has.
 */
@AutoConfigureMockMvc
class LazyAssociationsPostgresIT extends PostgresIntegrationTestBase {

    @MockitoBean private BookingServiceClient bookingServiceClient;
    @MockitoBean private EventServiceClient eventServiceClient;

    @Autowired private MockMvc mvc;
    @Autowired private SeatCategoryRepository categoryRepository;
    @Autowired private SeatRepository seatRepository;
    @Autowired private SeatService seatService;
    @Autowired private PlatformTransactionManager txManager;
    @Autowired private EntityManagerFactory emf;
    @Autowired private JdbcTemplate jdbc;

    @Value("${jwt.secret}")
    private String jwtSecret;

    private TransactionTemplate tx;
    private Statistics stats;
    private final Map<String, Long> measured = new LinkedHashMap<>();

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(txManager);
        stats = emf.unwrap(SessionFactory.class).getStatistics();
        assertThat(stats.isStatisticsEnabled()).as("hibernate.generate_statistics in the it profile").isTrue();
        clean();
        when(bookingServiceClient.fetchActiveCountsByCategories(any())).thenReturn(Optional.of(Map.of()));
    }

    @AfterEach
    void tearDown() {
        measured.forEach((k, v) -> System.out.println("[query-count] " + k + " = " + v));
        clean();
    }

    private void clean() {
        jdbc.update("DELETE FROM seats");
        jdbc.update("DELETE FROM seat_categories");
    }

    @Test
    void categoryListing_keepsItsShape_andCostsTheSameForTwoOrSixCategories() throws Exception {
        UUID small = seedEvent(2);
        UUID large = seedEvent(6);

        for (UUID eventId : List.of(small, large)) {
            int categories = eventId.equals(small) ? 2 : 6;
            measure("[C=" + categories + "] GET /seat-categories?eventId", get("/seat-categories")
                            .param("eventId", eventId.toString()),
                    r -> r.andExpect(jsonPath("$.data", hasSize(categories)))
                            // Sections in the order they were created — NOT
                            // alphabetical — with the per-section seat counts
                            // and the section image stamped on its seats.
                            .andExpect(jsonPath("$.data[0].sections[*].section", contains("STAND", "BOX", "LAWN")))
                            .andExpect(jsonPath("$.data[0].sections[*].seatCount", contains(5, 3, 2)))
                            .andExpect(jsonPath("$.data[0].sections[0].imageUrl").doesNotExist())
                            .andExpect(jsonPath("$.data[0].sections[1].imageUrl").value("https://cdn.example.com/box.png"))
                            .andExpect(jsonPath("$.data[*].availableSeats", everyItem(is(10)))));
        }
        assertThat(measured.get("[C=6] GET /seat-categories?eventId"))
                .isEqualTo(measured.get("[C=2] GET /seat-categories?eventId"))
                .isEqualTo(2);
    }

    @Test
    void seatEndpoints_renderTheCategory() throws Exception {
        UUID eventId = seedEvent(1);
        SeatCategory category = categoryRepository.findByEventIdAndDeletedFalse(eventId).get(0);
        UUID categoryId = category.getId();
        UUID seatId = jdbc.queryForObject(
                "SELECT id FROM seats WHERE category_id = ? AND row_label = 'STAND' AND seat_number = 1",
                UUID.class, categoryId);

        measure("GET /seats?categoryId", get("/seats").param("categoryId", categoryId.toString()),
                r -> r.andExpect(jsonPath("$.data", hasSize(10)))
                        .andExpect(jsonPath("$.data[*].categoryName", everyItem(is("Category 1")))));
        measure("GET /seats/available?categoryId", get("/seats/available").param("categoryId", categoryId.toString()),
                r -> r.andExpect(jsonPath("$.data", hasSize(10)))
                        .andExpect(jsonPath("$.data[*].categoryName", everyItem(is("Category 1")))));
        measure("GET /seats/available?categoryId&limit=3", get("/seats/available")
                        .param("categoryId", categoryId.toString()).param("limit", "3"),
                r -> r.andExpect(jsonPath("$.data", hasSize(3)))
                        .andExpect(jsonPath("$.data[*].categoryName", everyItem(is("Category 1")))));
        measure("GET /seats/{id}/lookup", get("/seats/" + seatId + "/lookup"),
                r -> r.andExpect(jsonPath("$.data.categoryName").value("Category 1"))
                        .andExpect(jsonPath("$.data.eventId").value(eventId.toString())));

        measure("POST /seats/{id}/lock", post("/seats/" + seatId + "/lock").header("Authorization", bearer("CUSTOMER")),
                r -> r.andExpect(jsonPath("$.data.categoryName").value("Category 1")));
        measure("POST /seats/{id}/confirm", post("/seats/" + seatId + "/confirm").header("Authorization", bearer("CUSTOMER")),
                r -> r.andExpect(jsonPath("$.data.categoryName").value("Category 1"))
                        .andExpect(jsonPath("$.data.status").value("BOOKED")));

        UUID second = jdbc.queryForObject(
                "SELECT id FROM seats WHERE category_id = ? AND row_label = 'STAND' AND seat_number = 2",
                UUID.class, categoryId);
        mvc.perform(post("/seats/" + second + "/lock").header("Authorization", bearer("CUSTOMER")));
        measure("POST /seats/{id}/release", post("/seats/" + second + "/release").header("Authorization", bearer("CUSTOMER")),
                r -> r.andExpect(jsonPath("$.code").exists()));

        // Reaper path: a LOCKED seat whose hold has lapsed.
        UUID third = jdbc.queryForObject(
                "SELECT id FROM seats WHERE category_id = ? AND row_label = 'STAND' AND seat_number = 3",
                UUID.class, categoryId);
        jdbc.update("UPDATE seats SET status = 'LOCKED', lock_expires_at = ? WHERE id = ?",
                LocalDateTime.now(ZoneOffset.UTC).minusMinutes(1), third);
        stats.clear();
        assertThat(seatService.releaseStaleLock(third)).isTrue();
        measured.put("SeatService.releaseStaleLock", stats.getPrepareStatementCount());

        assertThat(measured.get("GET /seats?categoryId")).isEqualTo(1);
        assertThat(measured.get("GET /seats/available?categoryId")).isEqualTo(1);
        assertThat(measured.get("GET /seats/{id}/lookup")).isEqualTo(1);
        assertThat(measured.get("POST /seats/{id}/confirm")).isEqualTo(2);
        // The counter UPDATEs need only the category's id, which the lazy
        // reference already holds: no category SELECT.
        assertThat(measured.get("POST /seats/{id}/release")).isEqualTo(3);
        assertThat(measured.get("SeatService.releaseStaleLock")).isEqualTo(3);
    }

    @Test
    void updateAndAnalytics_rebuildTheSections() throws Exception {
        UUID eventId = seedEvent(2);
        UUID categoryId = categoryRepository.findByEventIdAndDeletedFalse(eventId).get(0).getId();

        measure("PUT /seat-categories/{id}", put("/seat-categories/" + categoryId)
                        .header("Authorization", bearer("SUPER_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Renamed\",\"description\":\"d\",\"price\":12.50}"),
                r -> r.andExpect(jsonPath("$.data.name").value("Renamed"))
                        .andExpect(jsonPath("$.data.sections[*].section", contains("STAND", "BOX", "LAWN")))
                        .andExpect(jsonPath("$.data.sections[*].seatCount", contains(5, 3, 2))));

        // booking-service unreachable -> the per-category seat-status fallback
        // reads the seats table.
        when(bookingServiceClient.fetchBookingsByEvent(any(), any())).thenReturn(Optional.empty());
        measure("GET /seat-categories/analytics (seat fallback)", get("/seat-categories/analytics")
                        .param("eventId", eventId.toString()).header("Authorization", bearer("SUPER_ADMIN")),
                r -> r.andExpect(jsonPath("$.data.categories", hasSize(2)))
                        .andExpect(jsonPath("$.data.categories[0].seatStatusCounts.available").value(10)));
    }

    // ---- helpers -------------------------------------------------------------

    @FunctionalInterface
    private interface Expectations {
        void apply(ResultActions r) throws Exception;
    }

    private void measure(String name, MockHttpServletRequestBuilder request, Expectations expectations)
            throws Exception {
        stats.clear();
        ResultActions actions = mvc.perform(request);
        long count = stats.getPrepareStatementCount();
        MvcResult result = actions.andReturn();
        assertThat(result.getResponse().getStatus())
                .as("%s -> %s", name, result.getResponse().getContentAsString())
                .isLessThan(400);
        expectations.apply(actions);
        measured.put(name, count);
    }

    /**
     * One event with {@code categories} categories of 10 seats each, in three
     * sections created in NON-alphabetical order (STAND 5, BOX 3, LAWN 2), the
     * BOX section carrying an image.
     */
    private UUID seedEvent(int categories) {
        UUID eventId = UUID.randomUUID();
        tx.executeWithoutResult(s -> {
            for (int c = 1; c <= categories; c++) {
                SeatCategory category = categoryRepository.save(SeatCategory.builder()
                        .eventId(eventId)
                        .name("Category " + c)
                        .price(new BigDecimal("10.00"))
                        .totalSeats(10)
                        .availableSeats(10)
                        .deleted(false)
                        .build());
                List<Seat> seats = new ArrayList<>();
                addSection(seats, category, "STAND", 5, null);
                addSection(seats, category, "BOX", 3, "https://cdn.example.com/box.png");
                addSection(seats, category, "LAWN", 2, null);
                seatRepository.saveAll(seats);
            }
        });
        return eventId;
    }

    private static void addSection(List<Seat> seats, SeatCategory category, String label, int count, String image) {
        for (int n = 1; n <= count; n++) {
            seats.add(Seat.builder()
                    .category(category)
                    .sectionLabel(label)
                    .sectionImageUrl(image)
                    .seatNumber(n)
                    .status(Seat.SeatStatus.AVAILABLE)
                    .build());
        }
    }

    private String bearer(String role) {
        return "Bearer " + Jwts.builder()
                .subject("caller-" + role.toLowerCase() + "@example.com")
                .issuer(JwtUtil.TOKEN_ISSUER)
                .audience().add(JwtUtil.TOKEN_AUDIENCE).and()
                .claim("roles", List.of(role))
                .issuedAt(Date.from(Instant.now()))
                .expiration(Date.from(Instant.now().plusSeconds(600)))
                .signWith(Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8)))
                .compact();
    }
}
