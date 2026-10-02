package com.innbucks.bookingservice.messaging;

import com.innbucks.bookingservice.client.EmailNotificationClient;
import com.innbucks.bookingservice.client.EventServiceClient;
import com.innbucks.bookingservice.client.SmsNotificationClient;
import com.innbucks.bookingservice.client.UserServiceClient;
import com.innbucks.bookingservice.client.WhatsAppNotificationClient;
import com.innbucks.bookingservice.config.AsyncConfig;
import com.innbucks.bookingservice.config.CorrelationIdFilter;
import com.innbucks.bookingservice.dto.ApiResult;
import com.innbucks.bookingservice.dto.BookingResponseDTO;
import com.innbucks.bookingservice.dto.EventLookupDTO;
import com.innbucks.bookingservice.dto.TenantContactDTO;
import com.innbucks.bookingservice.entity.Booking;
import com.innbucks.bookingservice.entity.BookingItem;
import com.innbucks.bookingservice.repository.BookingItemRepository;
import com.innbucks.bookingservice.repository.BookingRepository;
import com.innbucks.bookingservice.service.BookingService;
import com.innbucks.bookingservice.service.EventReminderScheduler;
import com.innbucks.bookingservice.service.OrganizerEventReminderScheduler;
import com.innbucks.bookingservice.testsupport.PostgresIntegrationTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * End to end on real Postgres: no WhatsApp / SMS / email / event-service call
 * booking-service makes after a confirm or in a reminder run happens while a
 * transaction is open or a pooled connection is bound to the calling thread.
 *
 * <p>Every remote client is a Mockito bean whose answer RECORDS what the
 * calling thread looked like at the moment of the call — whether a
 * transaction was active, whether any resource (the JPA EntityManager /
 * JDBC connection holder) was bound, and the thread's name — so a regression
 * that moves a send back inside a transaction fails here rather than as pool
 * exhaustion in production.
 */
@TestPropertySource(properties = {
        // The reminder jobs are driven by hand below; never let the real cron
        // fire one mid-test.
        "app.booking.reminder-cron=-",
        "app.booking.organizer-reminder-cron=-"
})
class SendsOutsideTransactionsPostgresIT extends PostgresIntegrationTestBase {

    @MockitoBean private WhatsAppNotificationClient whatsApp;
    @MockitoBean private EmailNotificationClient email;
    @MockitoBean private SmsNotificationClient sms;
    @MockitoBean private EventServiceClient eventServiceClient;
    @MockitoBean private UserServiceClient userServiceClient;

    @Autowired private BookingService bookingService;
    @Autowired private BookingRepository bookingRepository;
    @Autowired private BookingItemRepository bookingItemRepository;
    @Autowired private EventReminderScheduler eventReminderScheduler;
    @Autowired private OrganizerEventReminderScheduler organizerReminderScheduler;
    @Autowired private PlatformTransactionManager txManager;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ApplicationContext context;

    @Value("${innbucks.internal-api-token}")
    private String internalToken;

    private TransactionTemplate tx;
    /** One line per remote call: "thread|txActive|resourcesBound" (+ extras). */
    private final Queue<String> calls = new ConcurrentLinkedQueue<>();

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(txManager);
        clean();
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
        clean();
    }

    private void clean() {
        jdbc.update("DELETE FROM organizer_event_reminders");
        jdbc.update("DELETE FROM booking_items");
        jdbc.update("DELETE FROM bookings");
        jdbc.update("DELETE FROM shedlock");
    }

    // ---- confirm -> ticket delivery + availability decrement ---------------

    @Test
    void confirm_returnsBeforeDelivery_whichRunsOnTheDeliveryPoolWithNoTransactionOrConnection()
            throws Exception {
        UUID bookingId = seedPending(UUID.randomUUID(), "+263771234567", "buyer@example.com", 2);
        CountDownLatch confirmReturned = new CountDownLatch(1);
        Queue<Boolean> sawConfirmReturn = new ConcurrentLinkedQueue<>();
        Queue<String> correlation = new ConcurrentLinkedQueue<>();
        doAnswer(inv -> {
            record("qr");
            correlation.add(String.valueOf(MDC.get(CorrelationIdFilter.MDC_KEY)));
            // If delivery still ran on the confirming thread, this would block
            // confirm itself and the latch could never open in time.
            sawConfirmReturn.add(confirmReturned.await(5, TimeUnit.SECONDS));
            return null;
        }).when(whatsApp).sendEventQrCode(anyString(), anyString(), anyString());
        doAnswer(recording("email")).when(email)
                .sendEmail(anyString(), anyString(), anyString(), anyString());

        MDC.put(CorrelationIdFilter.MDC_KEY, "it-corr-1");
        // The controller's entry point: the proxied two-arg form.
        BookingResponseDTO resp = bookingService.confirmBooking(bookingId, null);
        confirmReturned.countDown();

        assertThat(resp.getStatus()).isEqualTo(Booking.BookingStatus.CONFIRMED);
        verify(whatsApp, timeout(10_000).times(2)).sendEventQrCode(eq("+263771234567"), anyString(), anyString());
        verify(email, timeout(10_000)).sendEmail(eq("buyer@example.com"), anyString(), anyString(), anyString());

        assertThat(sawConfirmReturn).containsExactly(true, true);
        assertThat(correlation).as("MDC follows the delivery onto the pool").containsOnly("it-corr-1");
        assertAllCallsOffTransaction(3, true);
        assertThat(context.containsBean("applicationTaskExecutor"))
                .as("Boot's default @Async executor is kept beside ticketDeliveryExecutor").isTrue();
    }

    @Test
    void availabilityDecrement_runsAfterCommit_onceForANewConfirm_neverOnReplay() {
        UUID eventId = UUID.randomUUID();
        UUID bookingId = seedPending(eventId, "+263771234567", null, 3);
        doAnswer(recording("consume")).when(eventServiceClient)
                .consumeAvailability(any(), anyInt(), anyString());

        bookingService.confirmBooking(bookingId, null);
        verify(eventServiceClient, timeout(10_000)).consumeAvailability(eventId, 3, internalToken);

        // Idempotent replay of an already-CONFIRMED booking publishes nothing.
        BookingResponseDTO replay = bookingService.confirmBooking(bookingId, null);
        assertThat(replay.getStatus()).isEqualTo(Booking.BookingStatus.CONFIRMED);
        verify(eventServiceClient, after(1_500).times(1)).consumeAvailability(any(), anyInt(), anyString());
        verify(whatsApp, times(3)).sendEventQrCode(anyString(), anyString(), anyString());

        assertAllCallsOffTransaction(1, true);
    }

    @Test
    void availabilityDecrementFailure_neverFailsTheConfirm() {
        UUID eventId = UUID.randomUUID();
        UUID bookingId = seedPending(eventId, null, "buyer@example.com", 1);
        when(eventServiceClient.consumeAvailability(any(), anyInt(), anyString()))
                .thenThrow(new RuntimeException("event-service down"));

        BookingResponseDTO resp = bookingService.confirmBooking(bookingId, null);

        assertThat(resp.getStatus()).isEqualTo(Booking.BookingStatus.CONFIRMED);
        verify(eventServiceClient, timeout(10_000)).consumeAvailability(eventId, 1, internalToken);
        assertThat(statusOf(bookingId)).isEqualTo("CONFIRMED");
    }

    // ---- attendee reminders --------------------------------------------------

    @Test
    void dayOfReminder_claimsCommitBeforeSending_noTransactionDuringSends_neverSentTwice() {
        UUID eventId = UUID.randomUUID();
        UUID fresh = seedConfirmed(eventId, "+263771111111", "fresh@example.com", null);
        UUID alreadyReminded = seedConfirmed(eventId, "+263772222222", "old@example.com",
                LocalDateTime.now(ZoneOffset.UTC).minusHours(1));
        when(eventServiceClient.getEvent(eventId)).thenReturn(ApiResult.ok(EventLookupDTO.builder()
                .eventId(eventId).title("Harare Jazz Night")
                .startDateTime(LocalDateTime.now(ZoneOffset.UTC).plusHours(5)).build()));
        Queue<Boolean> stampCommittedBeforeSend = new ConcurrentLinkedQueue<>();
        doAnswer(inv -> {
            record("sms");
            // Read on a fresh connection: only a COMMITTED stamp is visible.
            stampCommittedBeforeSend.add(jdbc.queryForObject(
                    "SELECT reminder_sent_at IS NOT NULL FROM bookings WHERE id = ?",
                    Boolean.class, fresh));
            return null;
        }).when(sms).sendSms(anyString(), anyString(), anyString());
        doAnswer(recording("email")).when(email).sendEmail(anyString(), anyString(), anyString(), anyString());
        doAnswer(recording("whatsapp")).when(whatsApp).sendCustomNotification(anyString(), anyString());

        eventReminderScheduler.remind();
        jdbc.update("DELETE FROM shedlock"); // let the second run actually run
        eventReminderScheduler.remind();

        verify(sms, times(1)).sendSms(eq("+263771111111"), anyString(), anyString());
        verify(sms, never()).sendSms(eq("+263772222222"), anyString(), anyString());
        verify(email, times(1)).sendEmail(anyString(), anyString(), anyString(), anyString());
        verify(whatsApp, times(1)).sendCustomNotification(anyString(), anyString());
        assertThat(stampCommittedBeforeSend).containsExactly(true);
        assertThat(jdbc.queryForObject("SELECT reminder_sent_at IS NOT NULL FROM bookings WHERE id = ?",
                Boolean.class, alreadyReminded)).isTrue();
        // Sent from the run's own thread — the point is that nothing is bound
        // to it while it sends.
        assertAllCallsOffTransaction(3, false);
    }

    // ---- organizer reminder --------------------------------------------------

    @Test
    void organizerReminder_claimsTheMarkerRowFirst_sendsWithNoTransaction_once() {
        UUID eventId = UUID.randomUUID();
        UUID organizer = UUID.randomUUID();
        seedConfirmed(eventId, "+263773333333", null, null, organizer);
        when(eventServiceClient.getEventInternal(eventId, internalToken)).thenReturn(ApiResult.ok(
                EventLookupDTO.builder().eventId(eventId).tenantUserUuid(organizer).title("Pink Fun Run")
                        .startDateTime(LocalDateTime.now(ZoneOffset.UTC).plusHours(10)).build()));
        when(userServiceClient.lookupTenants(any(), eq(internalToken))).thenReturn(ApiResult.ok(List.of(
                new TenantContactDTO(organizer, "Chisipite", "Harare", "organizer@school.zw"))));
        Queue<Boolean> markerCommittedBeforeSend = new ConcurrentLinkedQueue<>();
        doAnswer(inv -> {
            record("org-email");
            markerCommittedBeforeSend.add(jdbc.queryForObject(
                    "SELECT count(*) = 1 FROM organizer_event_reminders WHERE event_id = ?",
                    Boolean.class, eventId));
            return null;
        }).when(email).sendEmail(anyString(), anyString(), anyString(), anyString());

        organizerReminderScheduler.remind();
        jdbc.update("DELETE FROM shedlock");
        organizerReminderScheduler.remind();

        verify(email, times(1)).sendEmail(eq("organizer@school.zw"), anyString(), anyString(), anyString());
        assertThat(markerCommittedBeforeSend).containsExactly(true);
        assertAllCallsOffTransaction(1, false);
    }

    // ---- helpers -------------------------------------------------------------

    private Answer<Object> recording(String what) {
        return inv -> {
            record(what);
            return null;
        };
    }

    private void record(String what) {
        calls.add(Thread.currentThread().getName()
                + "|" + TransactionSynchronizationManager.isActualTransactionActive()
                + "|" + !TransactionSynchronizationManager.getResourceMap().isEmpty()
                + "|" + what);
    }

    private void assertAllCallsOffTransaction(int expected, boolean onDeliveryPool) {
        assertThat(calls).hasSize(expected);
        assertThat(calls).allSatisfy(c -> {
            String[] parts = c.split("\\|");
            assertThat(parts[1]).as("transaction active during %s", c).isEqualTo("false");
            assertThat(parts[2]).as("resource bound during %s", c).isEqualTo("false");
            if (onDeliveryPool) {
                // Post-commit work must have left the committing thread.
                assertThat(parts[0]).as("thread of %s", c).startsWith(AsyncConfig.THREAD_NAME_PREFIX);
            }
        });
    }

    private String statusOf(UUID bookingId) {
        return jdbc.queryForObject("SELECT status FROM bookings WHERE id = ?", String.class, bookingId);
    }

    private UUID seedPending(UUID eventId, String phone, String emailAddr, int tickets) {
        return seed(eventId, Booking.BookingStatus.PENDING, phone, emailAddr, null, null, tickets);
    }

    private UUID seedConfirmed(UUID eventId, String phone, String emailAddr, LocalDateTime remindedAt) {
        return seedConfirmed(eventId, phone, emailAddr, remindedAt, UUID.randomUUID());
    }

    private UUID seedConfirmed(UUID eventId, String phone, String emailAddr, LocalDateTime remindedAt,
                               UUID organizer) {
        return seed(eventId, Booking.BookingStatus.CONFIRMED, phone, emailAddr, remindedAt, organizer, 1);
    }

    private UUID seed(UUID eventId, Booking.BookingStatus status, String phone, String emailAddr,
                      LocalDateTime remindedAt, UUID organizer, int tickets) {
        return tx.execute(s -> {
            Booking booking = Booking.builder()
                    .userEmail(emailAddr)
                    .phoneNumber(phone)
                    .customerName("Rufaro Moyo")
                    .eventId(eventId)
                    .confirmationNumber("INN-IT-" + UUID.randomUUID())
                    .status(status)
                    .totalAmount(new BigDecimal("20.00"))
                    .tenantUserUuid(organizer)
                    .expiresAt(status == Booking.BookingStatus.PENDING
                            ? LocalDateTime.now(ZoneOffset.UTC).plusMinutes(30) : null)
                    .reminderSentAt(remindedAt)
                    .reminder2dSentAt(remindedAt)
                    .build();
            bookingRepository.save(booking);
            List<BookingItem> items = new ArrayList<>();
            for (int i = 0; i < tickets; i++) {
                items.add(bookingItemRepository.save(BookingItem.builder()
                        .booking(booking)
                        .seatId(UUID.randomUUID())
                        .categoryId(UUID.randomUUID())
                        .rowLabel("A")
                        .seatNumber(i + 1)
                        .categoryName("General")
                        .priceAtBooking(new BigDecimal("10.00"))
                        .ticketNumber("TKT-IT-" + UUID.randomUUID())
                        .isActive(true)
                        .build()));
            }
            booking.setItems(items);
            return booking.getId();
        });
    }
}
