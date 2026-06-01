package zw.co.innbucks.coregateway;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import zw.co.innbucks.core.dto.messenger.NotificationDto;
import zw.co.innbucks.core.rest.client.MessengerClient;

/**
 * Synchronous wrapper around {@link MessengerClient}.
 *
 * <p>messenger-interface uses a submit-then-reconcile model: the POST confirms
 * the notification was ACCEPTED into its queue (NotificationDto.status starts
 * PENDING/QUEUED); actual handset delivery happens asynchronously on its side
 * and is reconciled later via the status / upstreamReference fields.
 *
 * <p>We call it SYNCHRONOUSLY on purpose so the submission outcome propagates
 * back to the HTTP caller: a failure (messenger-interface down or rejecting)
 * must reach the ticketing caller so it can fall back to another channel
 * (user-service falls back to WhatsApp for OTPs/approvals). OpenFeign does not
 * honour the {@code @Async} on the core client interface anyway, so this call
 * blocks until messenger-interface responds.
 */
@Service
class MessengerService {

    private static final Logger log = LoggerFactory.getLogger(MessengerService.class);

    private final MessengerClient messengerClient;

    MessengerService(MessengerClient messengerClient) {
        this.messengerClient = messengerClient;
    }

    /** Submit the notification; throws on a non-2xx or connectivity failure. */
    void send(NotificationDto notification) {
        messengerClient.sendNotification(notification);
        log.info("[messenger] SMS submitted reference={} destination={}",
                notification.getReference(), notification.getDestination());
    }
}
