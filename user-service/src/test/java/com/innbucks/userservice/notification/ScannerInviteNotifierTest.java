package com.innbucks.userservice.notification;

import com.innbucks.userservice.client.NotificationDeliveryException;
import com.innbucks.userservice.client.SmsNotificationClient;
import com.innbucks.userservice.client.WhatsAppNotificationClient;
import com.innbucks.userservice.util.SmsTextSanitizer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static com.innbucks.userservice.notification.ScannerInviteNotifier.InviteRole.SCAN;
import static com.innbucks.userservice.notification.ScannerInviteNotifier.InviteRole.SELL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * The gate-scanner invite message: its fixed wording, the one-line rule for
 * the two names it carries, and WhatsApp first with SMS behind it.
 */
class ScannerInviteNotifierTest {

    private static final String PHONE = "+263771234567";
    private static final UUID SCANNER = UUID.fromString("4b1e2c3d-5a6f-4b7c-8d9e-0f1a2b3c4d5e");

    private WhatsAppNotificationClient whatsApp;
    private SmsNotificationClient sms;
    private SimpleMeterRegistry meters;
    private ScannerInviteNotifier notifier;

    @BeforeEach
    void setUp() {
        whatsApp = mock(WhatsAppNotificationClient.class);
        sms = mock(SmsNotificationClient.class);
        meters = new SimpleMeterRegistry();
        notifier = new ScannerInviteNotifier(whatsApp, sms, meters);
    }

    private double count(String outcome) {
        return meters.get(ScannerInviteNotifier.METRIC).tag("outcome", outcome).counter().count();
    }

    @Test
    void theMessage_namesTheOrganizerAndTheEvent_andSaysWhatToDo() {
        assertThat(ScannerInviteNotifier.message("Harare Arena Events", "Jazz on the Lawn")).isEqualTo(
                "Harare Arena Events added you as gate staff for Jazz on the Lawn. Open the InnBucks app to "
                        + "accept, then scan tickets there on the day. If you don't know this organiser, ignore "
                        + "this message.");
    }

    @Test
    void withoutANameOrATitle_theMessageStillReads() {
        assertThat(ScannerInviteNotifier.message(null, "  ")).isEqualTo(
                "An event organiser added you as gate staff on InnBucks. Open the InnBucks app to accept, then "
                        + "scan tickets there on the day. If you don't know this organiser, ignore this message.");
    }

    @Test
    void aSalesAgent_isToldToSellThere() {
        assertThat(ScannerInviteNotifier.message("Harare Arena Events", "Jazz on the Lawn", EnumSet.of(SELL)))
                .isEqualTo("Harare Arena Events added you as a sales agent for Jazz on the Lawn. Open the "
                        + "InnBucks app to accept, then sell tickets there. If you don't know this organiser, "
                        + "ignore this message.");
    }

    @Test
    void bothRoles_namesBoth_andOnlyAsksToAccept() {
        assertThat(ScannerInviteNotifier.message("Harare Arena Events", "Jazz on the Lawn", EnumSet.of(SCAN, SELL)))
                .isEqualTo("Harare Arena Events added you as gate staff and a sales agent for Jazz on the Lawn. "
                        + "Open the InnBucks app to accept. If you don't know this organiser, ignore this message.");
    }

    @Test
    void scanOnly_orNoRoles_isTodaysMessage() {
        String today = ScannerInviteNotifier.message("Harare Arena Events", "Jazz on the Lawn");
        assertThat(ScannerInviteNotifier.message("Harare Arena Events", "Jazz on the Lawn", EnumSet.of(SCAN)))
                .isEqualTo(today);
        assertThat(ScannerInviteNotifier.message("Harare Arena Events", "Jazz on the Lawn", null)).isEqualTo(today);
        assertThat(ScannerInviteNotifier.message("Harare Arena Events", "Jazz on the Lawn",
                EnumSet.noneOf(ScannerInviteNotifier.InviteRole.class))).isEqualTo(today);
    }

    @Test
    void roles_areParsedLeniently_absentOrUnknownMeansScan() {
        assertThat(ScannerInviteNotifier.InviteRole.parse(null)).containsExactly(SCAN);
        assertThat(ScannerInviteNotifier.InviteRole.parse(List.of())).containsExactly(SCAN);
        assertThat(ScannerInviteNotifier.InviteRole.parse(List.of("USHER"))).containsExactly(SCAN);
        assertThat(ScannerInviteNotifier.InviteRole.parse(List.of("SELL"))).containsExactly(SELL);
        assertThat(ScannerInviteNotifier.InviteRole.parse(java.util.Arrays.asList(" sell ", null, "USHER")))
                .containsExactly(SELL);
        assertThat(ScannerInviteNotifier.InviteRole.parse(List.of("SELL", "SCAN"))).containsExactly(SCAN, SELL);
    }

    @Test
    void theFixedWords_surviveTheSmsGatewayUnchanged() {
        // The gateway refuses ! : / ? " * ; and the sanitizer rewrites them, so
        // a fixed sentence that round-trips unchanged is one the SMS carries as
        // written. Every role set, with and without the two names.
        for (Set<ScannerInviteNotifier.InviteRole> roles : List.<Set<ScannerInviteNotifier.InviteRole>>of(
                EnumSet.of(SCAN), EnumSet.of(SELL), EnumSet.of(SCAN, SELL))) {
            for (String text : new String[] {
                    ScannerInviteNotifier.message("Harare Arena Events", "Jazz on the Lawn", roles),
                    ScannerInviteNotifier.message(null, null, roles)}) {
                assertThat(SmsTextSanitizer.toGsmSafe(text)).as(text).isEqualTo(text);
                assertThat(text).as("one line").doesNotContain("\n", "\r");
            }
        }
    }

    @Test
    void aSalesAgentInvite_goesByWhatsAppWithTheSellWording() {
        notifier.send(PHONE, "Harare Arena Events", "Jazz on the Lawn", SCANNER, EnumSet.of(SELL));

        verify(whatsApp).sendCustomNotification(PHONE,
                ScannerInviteNotifier.message("Harare Arena Events", "Jazz on the Lawn", EnumSet.of(SELL)));
        verifyNoInteractions(sms);
    }

    @Test
    void aNameOrTitle_isFlattenedToOneLine_soItCannotForgeASecondParagraph() {
        String text = ScannerInviteNotifier.message("Harare\nArena‮Events",
                "Jazz\r\n\r\nCall 0771234567 now​ ");
        assertThat(text).doesNotContain("\n", "\r", "‮", "​", " ");
        assertThat(text).startsWith("Harare Arena Events added you as gate staff for Jazz Call 0771234567 now.");
    }

    @Test
    void longNames_areCapped() {
        String text = ScannerInviteNotifier.message("O".repeat(300), "T".repeat(300));
        assertThat(text).contains("O".repeat(ScannerInviteNotifier.MAX_ORGANIZER_NAME) + " added")
                .doesNotContain("O".repeat(ScannerInviteNotifier.MAX_ORGANIZER_NAME + 1))
                .contains("for " + "T".repeat(ScannerInviteNotifier.MAX_EVENT_TITLE) + ". Open")
                .doesNotContain("T".repeat(ScannerInviteNotifier.MAX_EVENT_TITLE + 1));
    }

    @Test
    void whatsApp_isTriedFirst_andAnSmsIsNotSentWhenItWorks() {
        notifier.send(PHONE, "Harare Arena Events", "Jazz on the Lawn", SCANNER);

        verify(whatsApp).sendCustomNotification(eq(PHONE), contains("Jazz on the Lawn"));
        verifyNoInteractions(sms);
        assertThat(count("whatsapp")).isEqualTo(1.0);
        assertThat(count("sms")).isZero();
    }

    @Test
    void whenWhatsAppFails_theSameTextGoesBySms() {
        doThrow(new NotificationDeliveryException("HTTP 502")).when(whatsApp).sendCustomNotification(any(), any());

        notifier.send(PHONE, "Harare Arena Events", "Jazz on the Lawn", SCANNER);

        verify(sms).sendSms(PHONE, ScannerInviteNotifier.message("Harare Arena Events", "Jazz on the Lawn"),
                "SCANNER-INVITE-" + SCANNER);
        assertThat(count("sms")).isEqualTo(1.0);
        assertThat(count("whatsapp")).isZero();
    }

    @Test
    void whenBothFail_nothingIsThrown_andItIsCounted() {
        doThrow(new NotificationDeliveryException("HTTP 502")).when(whatsApp).sendCustomNotification(any(), any());
        doThrow(new NotificationDeliveryException("HTTP 400")).when(sms).sendSms(any(), any(), any());

        notifier.send(PHONE, "Harare Arena Events", null, SCANNER);

        assertThat(count("failed")).isEqualTo(1.0);
    }

    @Test
    void everyOutcome_isRegisteredAtZero_soTheFirstFailureIsVisible() {
        assertThat(count("whatsapp")).isZero();
        assertThat(count("sms")).isZero();
        assertThat(count("failed")).isZero();
    }
}
