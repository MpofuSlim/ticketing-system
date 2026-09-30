package com.innbucks.userservice.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.innbucks.userservice.client.EmailNotificationClient;
import com.innbucks.userservice.exception.StaffPolicyException;
import com.innbucks.userservice.testsupport.StaffFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Invites are provisioned when the cell has a console URL AND at least one
 * email transport (SES/SMTP or the notification API). Anything less is a 503
 * {@code staff_invites_unconfigured} at request time and a HALF-PROVISIONED
 * ERROR at boot — the failure is otherwise silent until someone creates staff.
 */
class StaffProvisioningCheckTest {

    private final EmailNotificationClient email = mock(EmailNotificationClient.class);
    private ListAppender<ILoggingEvent> logs;

    @BeforeEach
    void attach() {
        logs = new ListAppender<>();
        logs.start();
        ((Logger) LoggerFactory.getLogger(StaffProvisioningCheck.class)).addAppender(logs);
    }

    @AfterEach
    void detach() {
        ((Logger) LoggerFactory.getLogger(StaffProvisioningCheck.class)).detachAppender(logs);
    }

    private List<String> errors() {
        return logs.list.stream().filter(e -> e.getLevel() == Level.ERROR).map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    @Test
    @DisplayName("URL + SMTP, or URL + API: provisioned, no ERROR, the resolved link logged at INFO")
    void provisioned() {
        when(email.smtpEnabled()).thenReturn(true);
        StaffProvisioningCheck check = new StaffProvisioningCheck(StaffFixtures.properties(), email);
        assertThat(check.invitesProvisioned()).isTrue();
        assertThatCode(check::requireInvitesProvisioned).doesNotThrowAnyException();
        check.checkStaffProvisioning();
        assertThat(errors()).isEmpty();
        assertThat(logs.list).anySatisfy(e -> assertThat(e.getFormattedMessage())
                .startsWith("Staff invites link to https://foundry.innbucks.co.zw/set-password#token=…"));

        when(email.smtpEnabled()).thenReturn(false);
        when(email.apiConfigured()).thenReturn(true);
        assertThat(check.invitesProvisioned()).isTrue();
    }

    @Test
    @DisplayName("neither transport: 503 staff_invites_unconfigured and a HALF-PROVISIONED ERROR")
    void noTransport() {
        StaffProvisioningCheck check = new StaffProvisioningCheck(StaffFixtures.properties(), email);
        assertThat(check.invitesProvisioned()).isFalse();
        assertThatThrownBy(check::requireInvitesProvisioned).isInstanceOf(StaffPolicyException.class)
                .extracting("errorCode").isEqualTo("staff_invites_unconfigured");
        check.checkStaffProvisioning();
        assertThat(errors()).singleElement().asString().startsWith("Staff accounts HALF-PROVISIONED: no email transport");
    }

    @Test
    @DisplayName("blank console URL or blank domains: each is its own HALF-PROVISIONED ERROR")
    void blankConfig() {
        when(email.apiConfigured()).thenReturn(true);
        StaffAccountProperties blank = new StaffAccountProperties();
        blank.afterPropertiesSet();
        StaffProvisioningCheck check = new StaffProvisioningCheck(blank, email);
        assertThat(check.invitesProvisioned()).isFalse();
        check.checkStaffProvisioning();
        assertThat(errors()).hasSize(2);
        assertThat(errors()).anySatisfy(m -> assertThat(m).contains("STAFF_ALLOWED_EMAIL_DOMAINS is blank"));
        assertThat(errors()).anySatisfy(m -> assertThat(m).contains("STAFF_CONSOLE_BASE_URL is blank"));
    }
}
