package zw.co.innbucks.coregateway;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import zw.co.innbucks.core.dto.messenger.NotificationRequest;
import zw.co.innbucks.core.rest.client.NotificationClient;

/**
 * Synchronous wrapper around {@link NotificationClient} (canonical v1 path,
 * {@code POST /api/v1/notifications}).
 *
 * <p>Same submit-then-reconcile contract as {@link MessengerService}: the POST
 * confirms messenger-interface ACCEPTED the notification into its queue, and
 * actual handset/inbox delivery is reconciled asynchronously on its side and
 * looked up later by {@code reference}. We call synchronously on purpose so a
 * rejection / unreachable upstream surfaces back to the HTTP caller and the
 * controller can fall back or 5xx — OpenFeign ignores {@code @Async} on the
 * core client interface anyway.
 */
@Service
class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final NotificationClient notificationClient;

    NotificationService(NotificationClient notificationClient) {
        this.notificationClient = notificationClient;
    }

    /** Submit the notification; throws on a non-2xx or connectivity failure. */
    void send(NotificationRequest request) {
        notificationClient.sendNotification(request);
        log.info("[messenger-v1] {} submitted reference={} destination={}",
                request.getChannel(), request.getReference(), request.getDestination());
    }
}
