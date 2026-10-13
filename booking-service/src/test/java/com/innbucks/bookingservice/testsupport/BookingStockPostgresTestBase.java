package com.innbucks.bookingservice.testsupport;

import com.innbucks.bookingservice.client.EmailNotificationClient;
import com.innbucks.bookingservice.client.EventServiceClient;
import com.innbucks.bookingservice.client.SeatServiceClient;
import com.innbucks.bookingservice.client.SmsNotificationClient;
import com.innbucks.bookingservice.client.UserServiceClient;
import com.innbucks.bookingservice.client.WhatsAppNotificationClient;
import com.innbucks.bookingservice.config.BookingMetrics;
import com.innbucks.bookingservice.dto.ApiResult;
import com.innbucks.bookingservice.dto.CategoryLookupDTO;
import com.innbucks.bookingservice.entity.Booking;
import com.innbucks.bookingservice.entity.BookingItem;
import com.innbucks.bookingservice.repository.BookingItemRepository;
import com.innbucks.bookingservice.repository.BookingRepository;
import com.innbucks.bookingservice.security.JwtUtil;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.Mockito.when;

/**
 * Shared setup for the ITs that drive booking stock against real Postgres:
 * every outbound client mocked, every background job kept out of the way,
 * MockMvc with the real filter chain, and the helpers the lock tests need —
 * a row lock held from ANOTHER connection, and a wait for a backend to block
 * on it.
 *
 * <p><b>The expiry sweep is isolated by its ShedLock row.</b> Every cached
 * Spring context in the IT run keeps its own scheduler, some on the default
 * 30 s poll, all sweeping the one shared database. {@link #holdTheSweepLock()}
 * marks the sweep's lock taken for an hour, so no context's scheduled sweep
 * runs while a test sets up lapsed holds; tests then run the sweep's TARGET
 * object directly, which does not go through ShedLock.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "app.booking.reminder-cron=-",
        "app.booking.organizer-reminder-cron=-",
        "app.booking.expiration-poll-interval-ms=86400000",
        "app.invoicing.scheduler-enabled=false",
        "innbucks.scan.event-day-check.enabled=false"
})
public abstract class BookingStockPostgresTestBase extends PostgresIntegrationTestBase {

    protected static final String SWEEP_LOCK = "BookingExpirationService.expirePending";

    @MockitoBean protected WhatsAppNotificationClient whatsApp;
    @MockitoBean protected EmailNotificationClient email;
    @MockitoBean protected SmsNotificationClient sms;
    @MockitoBean protected EventServiceClient eventServiceClient;
    @MockitoBean protected SeatServiceClient seatServiceClient;
    @MockitoBean protected UserServiceClient userServiceClient;

    @Autowired protected MockMvc mvc;
    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected DataSource dataSource;
    @Autowired protected MeterRegistry meterRegistry;
    @Autowired protected BookingRepository bookingRepository;
    @Autowired protected BookingItemRepository bookingItemRepository;
    @Autowired protected PlatformTransactionManager txManager;

    @Value("${jwt.secret}")
    private String jwtSecret;

    @Value("${innbucks.internal-api-token}")
    protected String internalToken;

    private final List<UUID> seededBookings = new ArrayList<>();
    private final List<UUID> seededCategories = new ArrayList<>();

    // ---- the sweep's ShedLock -----------------------------------------------

    protected void holdTheSweepLock() {
        jdbc.update("""
                INSERT INTO shedlock (name, lock_until, locked_at, locked_by)
                VALUES (?, timezone('utc', now()) + interval '1 hour', timezone('utc', now()), 'it-hold')
                ON CONFLICT (name) DO UPDATE SET lock_until = EXCLUDED.lock_until,
                    locked_at = EXCLUDED.locked_at, locked_by = EXCLUDED.locked_by
                """, SWEEP_LOCK);
    }

    /** Expire, never delete: ShedLock only UPDATEs a lock row it has seen. */
    protected void releaseTheSweepLock() {
        jdbc.update("UPDATE shedlock SET lock_until = TIMESTAMP '2000-01-01 00:00:00' WHERE name = ?", SWEEP_LOCK);
    }

    // ---- row locks from another connection ----------------------------------

    /** Opens a connection, begins a transaction and takes the booking row's lock. Close it to release. */
    protected Connection lockBookingRowElsewhere(UUID bookingId) throws SQLException {
        Connection connection = dataSource.getConnection();
        connection.setAutoCommit(false);
        try (PreparedStatement ps = connection.prepareStatement("SELECT id FROM bookings WHERE id = ? FOR UPDATE")) {
            ps.setObject(1, bookingId);
            ps.executeQuery().close();
        }
        return connection;
    }

    /** True when the counter row can be locked right now, i.e. no transaction holds it. */
    protected boolean counterRowIsFree(UUID categoryId) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT category_id FROM category_inventory WHERE category_id = ? FOR UPDATE NOWAIT")) {
                ps.setObject(1, categoryId);
                ps.executeQuery().close();
                return true;
            } catch (SQLException e) {
                if ("55P03".equals(e.getSQLState())) {
                    return false;
                }
                throw e;
            } finally {
                connection.rollback();
            }
        }
    }

    /** Waits until some backend of this database is blocked on a lock. */
    protected void awaitALockWaiter() throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            Integer waiting = jdbc.queryForObject("""
                    SELECT count(*) FROM pg_stat_activity
                     WHERE datname = current_database() AND wait_event_type = 'Lock'
                    """, Integer.class);
            if (waiting != null && waiting > 0) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("no backend started waiting on a lock within 10s");
    }

    // ---- fixtures -------------------------------------------------------------

    /** A category seat-service knows, with its counter row seeded at {@code remaining}. */
    protected void category(UUID eventId, UUID categoryId, String name, int remaining) {
        when(seatServiceClient.getCategory(categoryId)).thenReturn(ApiResult.ok(CategoryLookupDTO.builder()
                .seatCategoryId(categoryId).eventId(eventId).name(name).price(new BigDecimal("10.00"))
                .totalSeats(remaining).build()));
        jdbc.update("INSERT INTO category_inventory (category_id, remaining) VALUES (?, ?) "
                + "ON CONFLICT (category_id) DO UPDATE SET remaining = EXCLUDED.remaining", categoryId, remaining);
        seededCategories.add(categoryId);
    }

    /** Deletes what {@link #category} and {@link #seedBooking} created; the database is shared by every IT. */
    protected void deleteSeeded() {
        for (UUID id : seededBookings) {
            jdbc.update("DELETE FROM booking_items WHERE booking_id = ?", id);
            jdbc.update("DELETE FROM bookings WHERE id = ?", id);
        }
        for (UUID id : seededCategories) {
            jdbc.update("DELETE FROM category_inventory WHERE category_id = ?", id);
        }
        seededBookings.clear();
        seededCategories.clear();
    }

    protected int remaining(UUID categoryId) {
        Integer value = jdbc.queryForObject("SELECT remaining FROM category_inventory WHERE category_id = ?",
                Integer.class, categoryId);
        return value == null ? -1 : value;
    }

    protected String statusOf(UUID bookingId) {
        return jdbc.queryForObject("SELECT status FROM bookings WHERE id = ?", String.class, bookingId);
    }

    /** A booking with one ticket per category id given, committed. */
    protected Booking seedBooking(Booking.BookingStatus status, LocalDateTime expiresAt, UUID... categories) {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        return tx.execute(s -> {
            BigDecimal total = new BigDecimal("10.00").multiply(BigDecimal.valueOf(categories.length));
            Booking booking = Booking.builder()
                    .userEmail("buyer@example.com")
                    .phoneNumber("+263771234567")
                    .customerName("Rufaro Moyo")
                    .eventId(UUID.randomUUID())
                    .confirmationNumber("INN-IT-" + UUID.randomUUID().toString().substring(0, 13).toUpperCase())
                    .status(status)
                    .totalAmount(total)
                    .cashAmount(status == Booking.BookingStatus.CONFIRMED ? total : null)
                    .expiresAt(expiresAt)
                    .build();
            bookingRepository.save(booking);
            List<BookingItem> items = new ArrayList<>();
            int n = 1;
            for (UUID category : categories) {
                items.add(bookingItemRepository.save(BookingItem.builder()
                        .booking(booking).seatId(UUID.randomUUID()).categoryId(category)
                        .rowLabel("GA").seatNumber(n++).categoryName("General")
                        .priceAtBooking(new BigDecimal("10.00"))
                        .ticketNumber("IT-" + UUID.randomUUID())
                        .isActive(true)
                        .build()));
            }
            booking.setItems(items);
            seededBookings.add(booking.getId());
            return booking;
        });
    }

    protected static LocalDateTime lapsed(int minutesAgo) {
        return LocalDateTime.now(ZoneOffset.UTC).minusMinutes(minutesAgo);
    }

    // ---- metrics + auth ---------------------------------------------------------

    protected double claimRetries(String outcome) {
        return meterRegistry.get(BookingMetrics.CLAIM_RETRY).tag("outcome", outcome).counter().count();
    }

    protected double expirySkipped() {
        return meterRegistry.get(BookingMetrics.EXPIRY_SKIPPED).counter().count();
    }

    protected String bearer(String subject, List<String> roles, Map<String, Object> extraClaims) {
        return "Bearer " + Jwts.builder()
                .subject(subject)
                .issuer(JwtUtil.TOKEN_ISSUER)
                .audience().add(JwtUtil.TOKEN_AUDIENCE).and()
                .claims(extraClaims)
                .claim("roles", roles)
                .issuedAt(Date.from(Instant.now()))
                .expiration(Date.from(Instant.now().plusSeconds(600)))
                .signWith(Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8)))
                .compact();
    }
}
