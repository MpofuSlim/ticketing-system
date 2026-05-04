package com.innbucks.loyaltyservice.integration;

import com.innbucks.loyaltyservice.entity.Voucher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Stub façade for SMS, WhatsApp, email, push, and POS distribution channels.
 * Integrations replace these methods later; for now we log so QA can observe
 * that the right notifications would have fired.
 */
@Component
public class NotificationGateway {

    private static final Logger log = LoggerFactory.getLogger(NotificationGateway.class);

    public void deliver(Voucher voucher, Voucher.DeliveryChannel channel) {
        if (channel == null || channel == Voucher.DeliveryChannel.NONE) return;
        switch (channel) {
            case SMS -> log.info("SMS voucher {} -> {}", voucher.getCode(), voucher.getAssigneePhone());
            case WHATSAPP -> log.info("WhatsApp voucher {} -> {}", voucher.getCode(), voucher.getAssigneePhone());
            case EMAIL -> log.info("Email voucher {} -> assignee {}", voucher.getCode(), voucher.getAssignedUserId());
            case PUSH -> log.info("Push voucher {} -> assignee {}", voucher.getCode(), voucher.getAssignedUserId());
            case POS -> log.info("POS voucher {} forwarded to terminal", voucher.getCode());
            case NONE -> { /* no-op */ }
        }
    }
}
