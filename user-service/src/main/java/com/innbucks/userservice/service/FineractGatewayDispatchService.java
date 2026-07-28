package com.innbucks.userservice.service;

import com.innbucks.userservice.client.NotificationDeliveryException;
import com.innbucks.userservice.client.SmsNotificationClient;
import com.innbucks.userservice.client.WhatsAppNotificationClient;
import com.innbucks.userservice.entity.FineractGatewayMessage;
import com.innbucks.userservice.repository.FineractGatewayMessageRepository;
import com.innbucks.userservice.util.MsisdnMasking;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * Send side of the Fineract Message Gateway facade. Runs on the shared
 * {@code notificationExecutor} pool AFTER the accept endpoint has already
 * returned its 202 — Fineract's contract is fire-and-forget with a later
 * delivery-report poll, so a slow channel gateway must never hold the
 * accept response open.
 *
 * <p>Routing is per row ({@code channel}, resolved from the Fineract provider
 * id at accept time): SMS rides {@link SmsNotificationClient} (the InnBucks
 * notification API), WhatsApp rides {@link WhatsAppNotificationClient}.
 * Deliberately NO cross-channel fallback: a Fineract campaign chose its
 * channel, and reporting an honest FAILED beats silently moving a WhatsApp
 * campaign onto SMS.
 *
 * <p>On a terminal status the message body is nulled — it may carry a
 * Fineract 2FA OTP (A02: no plaintext OTPs at rest). Bodies are never logged
 * either, same rule as the channel clients themselves.
 */
@Service
@Slf4j
public class FineractGatewayDispatchService {

    private final FineractGatewayMessageRepository repository;
    private final SmsNotificationClient smsClient;
    private final WhatsAppNotificationClient whatsAppClient;
    private final MeterRegistry meterRegistry;

    public FineractGatewayDispatchService(FineractGatewayMessageRepository repository,
                                          SmsNotificationClient smsClient,
                                          WhatsAppNotificationClient whatsAppClient,
                                          MeterRegistry meterRegistry) {
        this.repository = repository;
        this.smsClient = smsClient;
        this.whatsAppClient = whatsAppClient;
        this.meterRegistry = meterRegistry;
    }

    /**
     * Dispatch the given gateway rows (by OUR row id, not Fineract's).
     * Each row is sent and saved independently so one dead channel can't
     * wedge the rest of the batch.
     */
    @Async("notificationExecutor")
    public void dispatchAsync(List<Long> rowIds) {
        for (Long rowId : rowIds) {
            repository.findById(rowId).ifPresent(this::dispatchRow);
        }
    }

    private void dispatchRow(FineractGatewayMessage row) {
        if (row.getDeliveryStatus() != FineractGatewayMessage.STATUS_PENDING) {
            return; // re-queued and already handled, or invalid — nothing to send
        }
        String body = row.getMessage();
        if (body == null || body.isBlank()) {
            // PENDING without a body should be impossible (accept validates);
            // fail closed rather than NPE inside a channel client.
            terminal(row, FineractGatewayMessage.STATUS_FAILED, "Message body missing at dispatch time");
            return;
        }
        try {
            if (FineractGatewayMessageService.CHANNEL_WHATSAPP.equals(row.getChannel())) {
                whatsAppClient.sendCustomNotification(row.getMobileNumber(), body);
            } else {
                smsClient.sendSms(row.getMobileNumber(), body, row.getExternalRef());
            }
            row.setSentAt(Instant.now());
            terminal(row, FineractGatewayMessage.STATUS_SENT, null);
            log.info("Fineract gateway message sent fineractId={} channel={} to={}",
                    row.getFineractId(), row.getChannel(), MsisdnMasking.mask(row.getMobileNumber()));
        } catch (NotificationDeliveryException ex) {
            terminal(row, FineractGatewayMessage.STATUS_FAILED, truncate(ex.getMessage()));
            log.warn("Fineract gateway message failed fineractId={} channel={} to={} reason={}",
                    row.getFineractId(), row.getChannel(), MsisdnMasking.mask(row.getMobileNumber()), ex.getMessage());
        } catch (RuntimeException ex) {
            // A channel client bug must not strand the row PENDING — Fineract
            // would poll it as WAITING forever. Fail it and keep the batch moving.
            terminal(row, FineractGatewayMessage.STATUS_FAILED, truncate(ex.getMessage()));
            log.error("Fineract gateway dispatch error fineractId={} channel={}", row.getFineractId(), row.getChannel(), ex);
        }
    }

    private void terminal(FineractGatewayMessage row, int status, String error) {
        row.setDeliveryStatus(status);
        row.setErrorMessage(error);
        row.setMessage(null); // A02: body may carry an OTP — never terminal-at-rest
        repository.save(row);
        Counter.builder("fineract.gateway.messages")
                .description("Fineract Message Gateway dispatch outcomes")
                .tag("channel", row.getChannel())
                .tag("outcome", status == FineractGatewayMessage.STATUS_SENT ? "sent" : "failed")
                .register(meterRegistry)
                .increment();
    }

    private static String truncate(String message) {
        if (message == null) return "Delivery failed";
        return message.length() <= 500 ? message : message.substring(0, 500);
    }
}
