package com.innbucks.userservice.notification;

import com.innbucks.userservice.client.EmailNotificationClient;
import com.innbucks.userservice.config.MarketTimeZone;
import com.innbucks.userservice.event.SupportMfaResetNotice;
import com.innbucks.userservice.event.SupportMfaResetNotice.OwnerNotice;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Async;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * The owners' notice after a support MFA reset: sent only once the reset has
 * COMMITTED (a reset whose seal failed rolled back and must never announce
 * itself), best-effort per owner, and each owner told only about the businesses
 * THEY own.
 */
class SupportNotificationListenerTest {

    private EmailNotificationClient email;
    private SupportNotificationListener listener;

    private static final SupportMfaResetNotice NOTICE = new SupportMfaResetNotice(1042L, "Tariro Moyo",
            "tariro@example.com", List.of(
                    new OwnerNotice("alpha.owner@example.com", List.of("Alpha Foods")),
                    new OwnerNotice("beta.owner@example.com", List.of("Beta Hardware")),
                    new OwnerNotice("both@example.com", List.of("Alpha Foods", "Beta Hardware"))),
            LocalDateTime.of(2026, 9, 30, 10, 0));

    @BeforeEach
    void setUp() {
        email = mock(EmailNotificationClient.class);
        listener = new SupportNotificationListener(email, new MarketTimeZone("ZW"));
    }

    @Test
    @DisplayName("AFTER_COMMIT, async, and fallbackExecution — the phase is what keeps a rolled-back reset silent")
    void listenerPhase() throws Exception {
        var method = SupportNotificationListener.class.getMethod("onSupportMfaReset", SupportMfaResetNotice.class);
        TransactionalEventListener tel = method.getAnnotation(TransactionalEventListener.class);
        assertThat(tel).isNotNull();
        assertThat(tel.phase()).isEqualTo(TransactionPhase.AFTER_COMMIT);
        assertThat(tel.fallbackExecution()).isTrue();
        assertThat(method.getAnnotation(Async.class)).isNotNull();
    }

    @Test
    @DisplayName("one bounced owner does not stop the others")
    void perRecipientFailureIsContained() {
        doThrow(new RuntimeException("mailbox full")).when(email)
                .sendEmail(eq("alpha.owner@example.com"), anyString(), anyString(), anyString());
        listener.onSupportMfaReset(NOTICE);
        verify(email).sendEmail(eq("beta.owner@example.com"), eq("InnBucks Foundry security notice"), anyString(),
                eq("SUPPORT-MFA-RESET-1042"));
        verify(email).sendEmail(eq("both@example.com"), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("C1: each owner's email names ONLY the businesses that owner owns")
    void eachOwnerHearsOnlyAboutTheirBusinesses() {
        listener.onSupportMfaReset(NOTICE);
        verify(email).sendEmail(eq("alpha.owner@example.com"), anyString(), contains("an owner of Alpha Foods on"),
                anyString());
        verify(email).sendEmail(eq("beta.owner@example.com"), anyString(), contains("an owner of Beta Hardware on"),
                anyString());

        String alpha = listener.message(NOTICE, NOTICE.owners().get(0));
        assertThat(alpha).doesNotContain("Beta Hardware")
                .contains("Tariro Moyo (tariro@example.com), a member of that business")
                // 10:00 UTC is 12:00 in Harare: the market clock, never the server's.
                .contains("At 12:00 on 30 Sep 2026");
        String both = listener.message(NOTICE, NOTICE.owners().get(2));
        assertThat(both).contains("an owner of Alpha Foods, Beta Hardware on").contains("a member of those businesses");
    }

    @Test
    @DisplayName("no owners — nothing is sent")
    void noOwners() {
        listener.onSupportMfaReset(new SupportMfaResetNotice(1L, null, null, List.of(), null));
        verifyNoInteractions(email);
    }
}
