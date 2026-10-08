package com.innbucks.userservice.notification;

import com.innbucks.userservice.event.RegistrationRejected;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * The applicant's notice of a rejected registration: what it says, where it
 * goes, and that it is only ever sent after the rejection committed.
 */
class RegistrationRejectionListenerTest {

    private static final String REASON = "We couldn't verify the BPO number you gave.";

    @Test
    @DisplayName("sent to the applicant's email (WhatsApp fallback) with the reason, verbatim")
    void dispatchesTheNotice() {
        UserNotificationDispatcher dispatcher = mock(UserNotificationDispatcher.class);

        new RegistrationRejectionListener(dispatcher).onRegistrationRejected(new RegistrationRejected(
                57L, "rumbi@showtime.co.zw", "+263772999000", "Rumbi", "Showtime Events", REASON));

        verify(dispatcher).dispatch("rumbi@showtime.co.zw", "+263772999000",
                "Your InnBucks registration was not approved",
                "Hi Rumbi, your registration for Showtime Events on InnBucks was not approved.\n\n"
                        + "Reason: " + REASON + "\n\n"
                        + "You can register again once you have addressed this.");
    }

    @Test
    @DisplayName("a personal registration has no business to name")
    void noBusinessName() {
        String body = RegistrationRejectionListener.bodyFor(new RegistrationRejected(
                57L, "tawanda@example.com", "+263771234567", "Tawanda", null, REASON));

        assertThat(body).startsWith("Hi Tawanda, your registration on InnBucks was not approved.\n\n");
        assertThat(RegistrationRejectionListener.bodyFor(new RegistrationRejected(
                57L, "tawanda@example.com", "+263771234567", "Tawanda", "  ", REASON)))
                .isEqualTo(body);
    }

    @Test
    @DisplayName("no first name: the greeting still reads")
    void noFirstName() {
        assertThat(RegistrationRejectionListener.bodyFor(new RegistrationRejected(
                57L, "rumbi@showtime.co.zw", null, " ", "Showtime Events", REASON)))
                .startsWith("Hi, your registration for Showtime Events on InnBucks was not approved.");
    }

    @Test
    @DisplayName("the subject is plain ASCII (the notification API refuses anything else)")
    void asciiSubject() {
        assertThat(RegistrationRejectionListener.SUBJECT).matches("\\p{ASCII}+");
    }

    @Test
    @DisplayName("AFTER_COMMIT only, with no fallback: a rejection that rolled back tells nobody")
    void afterCommitOnly() throws NoSuchMethodException {
        Method handler = RegistrationRejectionListener.class
                .getMethod("onRegistrationRejected", RegistrationRejected.class);
        TransactionalEventListener listener = handler.getAnnotation(TransactionalEventListener.class);

        assertThat(listener).isNotNull();
        assertThat(listener.phase()).isEqualTo(TransactionPhase.AFTER_COMMIT);
        assertThat(listener.fallbackExecution()).isFalse();
        assertThat(handler.isAnnotationPresent(org.springframework.scheduling.annotation.Async.class)).isTrue();
    }
}
