package com.innbucks.userservice.notification;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.innbucks.common.email.BrandedEmailRenderer;
import com.innbucks.userservice.client.EmailNotificationClient;
import com.innbucks.userservice.client.NotificationDeliveryException;
import com.innbucks.userservice.config.MarketTimeZone;
import com.innbucks.userservice.entity.StaffInvite;
import com.innbucks.userservice.event.StaffInviteRequested;
import com.innbucks.userservice.repository.StaffInviteRepository;
import com.innbucks.userservice.security.StaffInviteTokens;
import com.innbucks.userservice.testsupport.StaffFixtures;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.client.HttpClientErrorException;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * The invite email (V44, owner decision D1): the ordinary email path with a
 * "Set your password" button, the link in the URL FRAGMENT, reference
 * {@code STAFF-INVITE-<inviteId>}, the outcome written to the invite row — and
 * the link, the token and the body never in a log line, on success or failure.
 */
class StaffInviteMailerTest {

    private final EmailNotificationClient email = mock(EmailNotificationClient.class);
    private final StaffInviteRepository invites = mock(StaffInviteRepository.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private StaffInviteMailer mailer;
    private ListAppender<ILoggingEvent> logs;
    private final String token = StaffInviteTokens.generate();

    @BeforeEach
    void setUp() {
        mailer = new StaffInviteMailer(email, StaffFixtures.properties(), invites, new MarketTimeZone("ZW"),
                mock(PlatformTransactionManager.class));
        mailer.setMeterRegistry(meters);
        logs = new ListAppender<>();
        logs.start();
        ((Logger) LoggerFactory.getLogger(StaffInviteMailer.class)).addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        ((Logger) LoggerFactory.getLogger(StaffInviteMailer.class)).detachAppender(logs);
    }

    private StaffInviteRequested event() {
        return new StaffInviteRequested(907L, "tariro.moyo@innbucks.co.zw", "Tariro", token,
                List.of("CALL_CENTER_AGENT"), "admin@innbucks.co.zw", LocalDateTime.of(2026, 10, 2, 8, 15));
    }

    private void assertNoSecretLogged() {
        assertThat(logs.list).isNotEmpty();
        for (ILoggingEvent e : logs.list) {
            assertThat(e.getFormattedMessage()).doesNotContain(token).doesNotContain("#token=")
                    .doesNotContain("Set your password here");
            assertThat(e.getThrowableProxy()).isNull();
        }
    }

    @Test
    @DisplayName("sent: the fragment link, the button, the reference; row marked SENT; nothing secret logged")
    void sent() {
        StaffInvite.Delivery outcome = mailer.deliver(event());

        assertThat(outcome).isEqualTo(StaffInvite.Delivery.SENT);
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<BrandedEmailRenderer.CallToAction> cta =
                ArgumentCaptor.forClass(BrandedEmailRenderer.CallToAction.class);
        verify(email).sendEmail(eq("tariro.moyo@innbucks.co.zw"), eq("Your InnBucks Foundry staff account"),
                body.capture(), eq("STAFF-INVITE-907"), cta.capture());
        String link = "https://foundry.innbucks.co.zw/set-password#token=" + token;
        assertThat(cta.getValue().label()).isEqualTo("Set your password");
        assertThat(cta.getValue().url()).isEqualTo(link);
        assertThat(body.getValue())
                .startsWith("Hi Tariro,")
                .contains("admin@innbucks.co.zw has created an InnBucks Foundry staff account for you "
                        + "(CALL_CENTER_AGENT).")
                .contains(link)
                // 08:15 UTC is 10.15 in Harare.
                .contains("expires at 10.15 on 2 Oct");
        verify(invites).markDelivery(eq(907L), eq("SENT"), any(LocalDateTime.class));
        assertThat(meters.counter(StaffInviteMailer.METRIC, "outcome", "sent").count()).isEqualTo(1.0);
        assertNoSecretLogged();
    }

    @Test
    @DisplayName("an upstream refusal that echoes the link: row FAILED, only reference + status logged")
    void upstreamRefusal() {
        String echo = "{\"errors\":[\"Invalid message: https://foundry.innbucks.co.zw/set-password#token="
                + token + "\"]}";
        HttpClientErrorException upstream = HttpClientErrorException.create(HttpStatus.BAD_REQUEST, "Bad Request",
                HttpHeaders.EMPTY, echo.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
        doThrow(new NotificationDeliveryException("Notification API rejected email: HTTP 400", upstream))
                .when(email).sendEmail(anyString(), anyString(), anyString(), anyString(),
                        any(BrandedEmailRenderer.CallToAction.class));

        assertThat(mailer.deliver(event())).isEqualTo(StaffInvite.Delivery.FAILED);

        verify(invites).markDelivery(eq(907L), eq("FAILED"), isNull());
        assertThat(meters.counter(StaffInviteMailer.METRIC, "outcome", "failed").count()).isEqualTo(1.0);
        assertThat(logs.list).anySatisfy(e -> assertThat(e.getFormattedMessage())
                .isEqualTo("Staff invite delivery failed ref=STAFF-INVITE-907 status=400"));
        assertNoSecretLogged();
    }

    @Test
    @DisplayName("any other failure: FAILED, the exception's class only")
    void unexpectedFailure() {
        doThrow(new IllegalStateException("boom " + token)).when(email).sendEmail(anyString(), anyString(),
                anyString(), anyString(), any(BrandedEmailRenderer.CallToAction.class));
        assertThat(mailer.deliver(event())).isEqualTo(StaffInvite.Delivery.FAILED);
        assertThat(logs.list).anySatisfy(e -> assertThat(e.getFormattedMessage())
                .isEqualTo("Staff invite delivery failed ref=STAFF-INVITE-907 error=IllegalStateException"));
        assertNoSecretLogged();
    }

    @Test
    @DisplayName("the event's toString never prints the token")
    void eventToString() {
        assertThat(event().toString()).doesNotContain(token).contains("inviteId=907");
    }
}
