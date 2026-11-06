package com.innbucks.userservice.notification;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.innbucks.common.email.BrandedEmailRenderer;
import com.innbucks.userservice.client.EmailNotificationClient;
import com.innbucks.userservice.client.NotificationDeliveryException;
import com.innbucks.userservice.config.MarketTimeZone;
import com.innbucks.userservice.config.SalesAgentInviteProperties;
import com.innbucks.userservice.event.SalesAgentInviteRequested;
import com.innbucks.userservice.security.SalesAgentInviteTokens;
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
import org.springframework.web.client.HttpClientErrorException;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * The sales-agent portal invite email (V56): the ordinary email path with a
 * branded button, the link's token in the URL FRAGMENT, reference
 * {@code SALES-AGENT-INVITE-<inviteId>}, and the link, the token and the body
 * never in a log line.
 */
class SalesAgentInviteMailerTest {

    private final EmailNotificationClient email = mock(EmailNotificationClient.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final String token = SalesAgentInviteTokens.generate();
    private SalesAgentInviteMailer mailer;
    private ListAppender<ILoggingEvent> logs;

    @BeforeEach
    void setUp() {
        SalesAgentInviteProperties properties = new SalesAgentInviteProperties();
        properties.afterPropertiesSet();
        mailer = new SalesAgentInviteMailer(email, StaffFixtures.properties(), properties, new MarketTimeZone("ZW"));
        mailer.setMeterRegistry(meters);
        logs = new ListAppender<>();
        logs.start();
        ((Logger) LoggerFactory.getLogger(SalesAgentInviteMailer.class)).addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        ((Logger) LoggerFactory.getLogger(SalesAgentInviteMailer.class)).detachAppender(logs);
    }

    private SalesAgentInviteRequested event(String organizerName) {
        return new SalesAgentInviteRequested(31L, "tendai.moyo@gmail.com", organizerName, token,
                LocalDateTime.of(2026, 11, 9, 8, 15));
    }

    private void assertNoSecretLogged() {
        assertThat(logs.list).isNotEmpty();
        for (ILoggingEvent e : logs.list) {
            assertThat(e.getFormattedMessage()).doesNotContain(token).doesNotContain("#token=")
                    .doesNotContain("Set up your sign-in here");
            assertThat(e.getThrowableProxy()).isNull();
        }
    }

    @Test
    @DisplayName("sent: the fragment link, the button, the reference, the organizer and the market-time expiry")
    void sent() {
        assertThat(mailer.deliver(event("Harare Arena Events"))).isTrue();

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<BrandedEmailRenderer.CallToAction> cta =
                ArgumentCaptor.forClass(BrandedEmailRenderer.CallToAction.class);
        verify(email).sendEmail(eq("tendai.moyo@gmail.com"), eq("Sell tickets on the InnBucks Foundry portal"),
                body.capture(), eq("SALES-AGENT-INVITE-31"), cta.capture());
        String link = "https://foundry.innbucks.co.zw/sales-agent-invite#token=" + token;
        assertThat(cta.getValue().label()).isEqualTo("Set up your sign-in");
        assertThat(cta.getValue().url()).isEqualTo(link);
        assertThat(body.getValue())
                .contains("Harare Arena Events has asked you to sell tickets for them on the InnBucks Foundry portal.")
                .contains(link)
                .contains("two-step verification")
                // 08:15 UTC is 10.15 in Harare.
                .contains("expires at 10.15 on 9 Nov")
                .contains("If you don't know this organiser, ignore this email.");
        assertThat(meters.counter(SalesAgentInviteMailer.METRIC, "outcome", "sent").count()).isEqualTo(1.0);
        assertNoSecretLogged();
    }

    @Test
    @DisplayName("no organizer name: a neutral one")
    void noOrganizerName() {
        mailer.deliver(event(null));
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(email).sendEmail(anyString(), anyString(), body.capture(), anyString(),
                any(BrandedEmailRenderer.CallToAction.class));
        assertThat(body.getValue()).contains("An event organiser has asked you to sell tickets");
    }

    @Test
    @DisplayName("an upstream refusal that echoes the link: false, only reference + status logged, never thrown")
    void upstreamRefusal() {
        String echo = "{\"errors\":[\"Invalid message: https://foundry.innbucks.co.zw/sales-agent-invite#token="
                + token + "\"]}";
        HttpClientErrorException upstream = HttpClientErrorException.create(HttpStatus.BAD_REQUEST, "Bad Request",
                HttpHeaders.EMPTY, echo.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
        doThrow(new NotificationDeliveryException("Notification API rejected email: HTTP 400", upstream))
                .when(email).sendEmail(anyString(), anyString(), anyString(), anyString(),
                        any(BrandedEmailRenderer.CallToAction.class));

        assertThat(mailer.deliver(event("Harare Arena Events"))).isFalse();

        assertThat(meters.counter(SalesAgentInviteMailer.METRIC, "outcome", "failed").count()).isEqualTo(1.0);
        assertThat(logs.list).anySatisfy(e -> assertThat(e.getFormattedMessage())
                .isEqualTo("Sales-agent invite delivery failed ref=SALES-AGENT-INVITE-31 status=400"));
        assertNoSecretLogged();
    }

    @Test
    @DisplayName("any other failure: false, the exception's class only")
    void unexpectedFailure() {
        doThrow(new IllegalStateException("boom " + token)).when(email).sendEmail(anyString(), anyString(),
                anyString(), anyString(), any(BrandedEmailRenderer.CallToAction.class));
        assertThat(mailer.deliver(event("Harare Arena Events"))).isFalse();
        assertThat(logs.list).anySatisfy(e -> assertThat(e.getFormattedMessage())
                .isEqualTo("Sales-agent invite delivery failed ref=SALES-AGENT-INVITE-31 error=IllegalStateException"));
        assertNoSecretLogged();
    }

    @Test
    @DisplayName("the event's toString never prints the token")
    void eventToString() {
        assertThat(event("x").toString()).doesNotContain(token).contains("inviteId=31");
    }
}
