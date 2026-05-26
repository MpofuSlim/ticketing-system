package com.innbucks.loyaltyservice.integration;

import com.innbucks.loyaltyservice.entity.Voucher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Façade over the voucher distribution channels. WhatsApp is wired to the real
 * gateway; SMS / EMAIL / PUSH / POS remain stubs (logged) until those channels
 * are integrated.
 */
@Component
public class NotificationGateway {

    private static final Logger log = LoggerFactory.getLogger(NotificationGateway.class);

    private final WhatsAppNotificationClient whatsApp;

    public NotificationGateway(WhatsAppNotificationClient whatsApp) {
        this.whatsApp = whatsApp;
    }

    public void deliver(Voucher voucher, Voucher.DeliveryChannel channel) {
        if (channel == null || channel == Voucher.DeliveryChannel.NONE) return;
        switch (channel) {
            case SMS -> log.info("SMS voucher {} -> {}", voucher.getCode(), voucher.getAssigneePhone());
            case WHATSAPP -> deliverViaWhatsApp(voucher);
            case EMAIL -> log.info("Email voucher {} -> assignee {}", voucher.getCode(), voucher.getAssignedUserId());
            case PUSH -> log.info("Push voucher {} -> assignee {}", voucher.getCode(), voucher.getAssignedUserId());
            case POS -> log.info("POS voucher {} forwarded to terminal", voucher.getCode());
        }
    }

    /**
     * Best-effort: the voucher is already issued + persisted by the caller, so
     * a gateway failure (or a missing phone) must NOT roll that back — we log
     * and move on, and the customer can be re-notified.
     */
    private void deliverViaWhatsApp(Voucher voucher) {
        String phone = voucher.getAssigneePhone();
        if (phone == null || phone.isBlank()) {
            log.warn("WhatsApp voucher {} has no assignee phone; skipping delivery", voucher.getCode());
            return;
        }
        try {
            whatsApp.sendCustomNotification(phone, buildMessage(voucher));
            log.info("WhatsApp voucher {} delivered to {}", voucher.getCode(), phone);
        } catch (RuntimeException ex) {
            log.warn("WhatsApp voucher {} delivery failed (voucher still issued): {}",
                    voucher.getCode(), ex.getMessage());
        }
    }

    private static String buildMessage(Voucher voucher) {
        StringBuilder sb = new StringBuilder("You've received an InnBucks voucher. Code: ")
                .append(voucher.getCode());
        if (voucher.getValue() != null) {
            sb.append(". Value: ").append(voucher.getValue());
            if (voucher.getCurrency() != null && !voucher.getCurrency().isBlank()) {
                sb.append(' ').append(voucher.getCurrency());
            }
        }
        if (voucher.getExpiresAt() != null) {
            sb.append(". Valid until ").append(voucher.getExpiresAt());
        }
        return sb.append(". Present this code to redeem.").toString();
    }
}
