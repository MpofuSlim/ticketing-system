package com.innbucks.loyaltyservice.integration;

import com.innbucks.loyaltyservice.entity.Voucher;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class NotificationGatewayTest {

    private Voucher voucher(String phone) {
        Voucher v = new Voucher();
        v.setCode("VCH-12345");
        v.setAssigneePhone(phone);
        return v;
    }

    @Test
    void whatsappChannel_sendsVoucherCodeToAssigneePhone() {
        WhatsAppNotificationClient whatsApp = mock(WhatsAppNotificationClient.class);

        new NotificationGateway(whatsApp).deliver(voucher("+263771234567"), Voucher.DeliveryChannel.WHATSAPP);

        verify(whatsApp).sendCustomNotification(eq("+263771234567"), contains("VCH-12345"));
    }

    @Test
    void whatsappChannel_swallowsGatewayFailure_soIssuanceIsNotRolledBack() {
        WhatsAppNotificationClient whatsApp = mock(WhatsAppNotificationClient.class);
        doThrow(new NotificationDeliveryException("gateway down"))
                .when(whatsApp).sendCustomNotification(anyString(), anyString());

        // Best-effort: must NOT throw even though the gateway failed.
        new NotificationGateway(whatsApp).deliver(voucher("+263771234567"), Voucher.DeliveryChannel.WHATSAPP);

        verify(whatsApp).sendCustomNotification(eq("+263771234567"), anyString());
    }

    @Test
    void whatsappChannel_withNoPhone_skipsSend() {
        WhatsAppNotificationClient whatsApp = mock(WhatsAppNotificationClient.class);

        new NotificationGateway(whatsApp).deliver(voucher(null), Voucher.DeliveryChannel.WHATSAPP);

        verifyNoInteractions(whatsApp);
    }

    @Test
    void noneAndStubChannels_doNotCallWhatsApp() {
        WhatsAppNotificationClient whatsApp = mock(WhatsAppNotificationClient.class);
        NotificationGateway gateway = new NotificationGateway(whatsApp);

        gateway.deliver(voucher("+263771234567"), Voucher.DeliveryChannel.NONE);
        gateway.deliver(voucher("+263771234567"), Voucher.DeliveryChannel.SMS);
        gateway.deliver(voucher("+263771234567"), Voucher.DeliveryChannel.EMAIL);

        verifyNoInteractions(whatsApp);
    }
}
