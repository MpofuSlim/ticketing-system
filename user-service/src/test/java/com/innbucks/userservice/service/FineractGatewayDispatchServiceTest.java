package com.innbucks.userservice.service;

import com.innbucks.userservice.client.NotificationDeliveryException;
import com.innbucks.userservice.client.SmsNotificationClient;
import com.innbucks.userservice.client.WhatsAppNotificationClient;
import com.innbucks.userservice.entity.FineractGatewayMessage;
import com.innbucks.userservice.repository.FineractGatewayMessageRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Pins the send-side rules of the Fineract Message Gateway facade: channel
 * routing to the right client, terminal statuses in Fineract's code space,
 * one failure never wedging the rest of a batch, and — A02 — the message
 * body (it can carry a Fineract 2FA OTP) being nulled the moment a row goes
 * terminal, on success AND failure alike.
 */
class FineractGatewayDispatchServiceTest {

    private final FineractGatewayMessageRepository repository = mock(FineractGatewayMessageRepository.class);
    private final SmsNotificationClient smsClient = mock(SmsNotificationClient.class);
    private final WhatsAppNotificationClient whatsAppClient = mock(WhatsAppNotificationClient.class);

    private FineractGatewayDispatchService service() {
        return new FineractGatewayDispatchService(repository, smsClient, whatsAppClient, new SimpleMeterRegistry());
    }

    private static FineractGatewayMessage pending(long rowId, String channel) {
        return FineractGatewayMessage.builder()
                .id(rowId).fineractId(rowId * 10).tenantId("default")
                .mobileNumber("+263771234567").message("Your OTP is 123456")
                .channel(channel).deliveryStatus(FineractGatewayMessage.STATUS_PENDING)
                .externalRef("FIN-GW-ref").createdAt(Instant.now())
                .build();
    }

    @Test
    void smsRow_ridesSmsClient_andGoesSent_withBodyCleared() {
        FineractGatewayMessage row = pending(1L, "SMS");
        when(repository.findById(1L)).thenReturn(Optional.of(row));

        service().dispatchAsync(List.of(1L));

        verify(smsClient).sendSms("+263771234567", "Your OTP is 123456", "FIN-GW-ref");
        verifyNoInteractions(whatsAppClient);
        assertThat(row.getDeliveryStatus()).isEqualTo(FineractGatewayMessage.STATUS_SENT);
        assertThat(row.getSentAt()).isNotNull();
        assertThat(row.getMessage()).isNull(); // A02: OTP never terminal-at-rest
        verify(repository).save(row);
    }

    @Test
    void whatsappRow_ridesWhatsAppClient() {
        FineractGatewayMessage row = pending(2L, "WHATSAPP");
        when(repository.findById(2L)).thenReturn(Optional.of(row));

        service().dispatchAsync(List.of(2L));

        verify(whatsAppClient).sendCustomNotification("+263771234567", "Your OTP is 123456");
        verifyNoInteractions(smsClient);
        assertThat(row.getDeliveryStatus()).isEqualTo(FineractGatewayMessage.STATUS_SENT);
    }

    @Test
    void deliveryFailure_goesFailed_withReasonCaptured_andBodyStillCleared() {
        FineractGatewayMessage row = pending(3L, "SMS");
        when(repository.findById(3L)).thenReturn(Optional.of(row));
        doThrow(new NotificationDeliveryException("InnBucks notify rejected: HTTP 503"))
                .when(smsClient).sendSms(any(), any(), any());

        service().dispatchAsync(List.of(3L));

        assertThat(row.getDeliveryStatus()).isEqualTo(FineractGatewayMessage.STATUS_FAILED);
        assertThat(row.getErrorMessage()).contains("HTTP 503");
        assertThat(row.getMessage()).isNull(); // cleared on failure too
        assertThat(row.getSentAt()).isNull();
        verify(repository).save(row);
    }

    @Test
    void oneDeadChannel_doesNotWedgeTheRestOfTheBatch() {
        FineractGatewayMessage broken = pending(4L, "WHATSAPP");
        FineractGatewayMessage fine = pending(5L, "SMS");
        when(repository.findById(4L)).thenReturn(Optional.of(broken));
        when(repository.findById(5L)).thenReturn(Optional.of(fine));
        doThrow(new NotificationDeliveryException("WhatsApp gateway is unreachable"))
                .when(whatsAppClient).sendCustomNotification(any(), any());

        service().dispatchAsync(List.of(4L, 5L));

        assertThat(broken.getDeliveryStatus()).isEqualTo(FineractGatewayMessage.STATUS_FAILED);
        assertThat(fine.getDeliveryStatus()).isEqualTo(FineractGatewayMessage.STATUS_SENT);
    }

    @Test
    void unexpectedRuntimeException_failsTheRow_ratherThanStrandingItPending() {
        // A stranded PENDING row polls back to Fineract as WAITING forever.
        FineractGatewayMessage row = pending(6L, "SMS");
        when(repository.findById(6L)).thenReturn(Optional.of(row));
        doThrow(new IllegalStateException("client bug")).when(smsClient).sendSms(any(), any(), any());

        service().dispatchAsync(List.of(6L));

        assertThat(row.getDeliveryStatus()).isEqualTo(FineractGatewayMessage.STATUS_FAILED);
        assertThat(row.getErrorMessage()).contains("client bug");
    }

    @Test
    void nonPendingRow_isNotResent() {
        FineractGatewayMessage row = pending(7L, "SMS");
        row.setDeliveryStatus(FineractGatewayMessage.STATUS_SENT);
        when(repository.findById(7L)).thenReturn(Optional.of(row));

        service().dispatchAsync(List.of(7L));

        verifyNoInteractions(smsClient, whatsAppClient);
        verify(repository, never()).save(any());
    }
}
