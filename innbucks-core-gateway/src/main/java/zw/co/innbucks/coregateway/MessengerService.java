package zw.co.innbucks.coregateway;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import zw.co.innbucks.core.dto.messenger.NotificationDto;
import zw.co.innbucks.core.rest.client.MessengerClient;

/**
 * Thin async wrapper around {@link MessengerClient}.
 *
 * <p>OpenFeign ignores {@code @Async} on the Feign interface itself — calling
 * {@code messengerClient.sendNotification()} directly is always synchronous.
 * Wrapping it here with {@code @Async} lets the HTTP caller get an immediate
 * 202 Accepted while the Feign call runs on a background thread. Failures are
 * logged but not re-thrown; delivery failures must be handled by the upstream
 * caller (retry, fallback channel, alerting).
 */
@Service
class MessengerService {

    private static final Logger log = LoggerFactory.getLogger(MessengerService.class);

    private final MessengerClient messengerClient;

    MessengerService(MessengerClient messengerClient) {
        this.messengerClient = messengerClient;
    }

    @Async
    void send(NotificationDto notification) {
        try {
            messengerClient.sendNotification(notification);
            log.info("[messenger] SMS dispatched reference={} destination={}",
                    notification.getReference(), notification.getDestination());
        } catch (Exception e) {
            log.error("[messenger] SMS dispatch failed reference={} destination={} error={}",
                    notification.getReference(), notification.getDestination(), e.getMessage(), e);
        }
    }
}
