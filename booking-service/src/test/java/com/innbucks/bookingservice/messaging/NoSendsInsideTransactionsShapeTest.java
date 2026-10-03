package com.innbucks.bookingservice.messaging;

import com.innbucks.bookingservice.config.AsyncConfig;
import com.innbucks.bookingservice.event.BookingDomainEvent;
import com.innbucks.bookingservice.service.EventChangeNotificationService;
import com.innbucks.bookingservice.service.EventReminderScheduler;
import com.innbucks.bookingservice.service.OrganizerEventReminderScheduler;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Async;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the annotation shape that keeps booking-service's WhatsApp / SMS /
 * email / Feign side effects off a held DB connection. Each of these looks
 * like a harmless tidy-up to undo, and none of them fails a functional test
 * when undone:
 *
 * <ul>
 *   <li>a SYNCHRONOUS AFTER_COMMIT listener still holds the committed
 *       transaction's connection (afterCommit runs before cleanup), and
 *       {@code REQUIRES_NEW} on it takes a second one — so the post-commit
 *       listeners must be {@code @Async} on the bounded delivery pool and
 *       carry no {@code @Transactional};</li>
 *   <li>a {@code @Transactional} on the broadcast or on a reminder run holds
 *       one connection across the whole fan-out.</li>
 * </ul>
 */
class NoSendsInsideTransactionsShapeTest {

    @Test
    void postCommitListeners_areAsyncOnTheDeliveryPool_andNotTransactional() throws Exception {
        assertAsyncAfterCommitListener(BookingConfirmedNotificationListener.class
                .getMethod("onBookingConfirmed", BookingDomainEvent.BookingConfirmed.class));
        assertAsyncAfterCommitListener(BookingCancelledNotificationListener.class
                .getMethod("onBookingCancelled", BookingDomainEvent.BookingCancelled.class));
        assertAsyncAfterCommitListener(EventAvailabilityConsumeListener.class
                .getMethod("onBookingConfirmed", BookingDomainEvent.BookingConfirmed.class));
    }

    @Test
    void broadcastAndReminderRuns_holdNoTransactionOfTheirOwn() throws Exception {
        Method broadcast = EventChangeNotificationService.class.getMethod("broadcast",
                java.util.UUID.class, String.class, String.class, String.class, String.class);
        assertThat(broadcast.isAnnotationPresent(Async.class)).isTrue();
        assertNotTransactional(broadcast);

        for (Method remind : new Method[] {
                EventReminderScheduler.class.getMethod("remind"),
                OrganizerEventReminderScheduler.class.getMethod("remind")}) {
            assertNotTransactional(remind);
            // Claiming per event is not a reason to drop the cross-replica lock.
            assertThat(remind.isAnnotationPresent(SchedulerLock.class))
                    .as("%s keeps its @SchedulerLock", remind.getDeclaringClass().getSimpleName())
                    .isTrue();
        }
    }

    private static void assertAsyncAfterCommitListener(Method m) {
        String name = m.getDeclaringClass().getSimpleName() + "." + m.getName();
        Async async = m.getAnnotation(Async.class);
        assertThat(async).as("%s is @Async", name).isNotNull();
        assertThat(async.value()).as("%s runs on the delivery pool", name)
                .isEqualTo(AsyncConfig.TICKET_DELIVERY_EXECUTOR);
        TransactionalEventListener listener = m.getAnnotation(TransactionalEventListener.class);
        assertThat(listener).as("%s is a transactional event listener", name).isNotNull();
        assertThat(listener.phase()).as("%s fires AFTER_COMMIT", name)
                .isEqualTo(TransactionPhase.AFTER_COMMIT);
        assertNotTransactional(m);
    }

    private static void assertNotTransactional(Method m) {
        String name = m.getDeclaringClass().getSimpleName() + "." + m.getName();
        assertThat(m.isAnnotationPresent(Transactional.class))
                .as("%s must not be @Transactional", name).isFalse();
        assertThat(m.getDeclaringClass().isAnnotationPresent(Transactional.class))
                .as("%s's class must not be @Transactional", name).isFalse();
    }
}
