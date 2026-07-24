package com.innbucks.bookingservice.service;

import com.innbucks.bookingservice.client.EmailNotificationClient;
import com.innbucks.bookingservice.client.NotificationDeliveryException;
import com.innbucks.bookingservice.client.UserServiceClient;
import com.innbucks.bookingservice.dto.ApiResult;
import com.innbucks.bookingservice.dto.TenantContactDTO;
import com.innbucks.bookingservice.entity.EventInvoice;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InvoiceNotifierTest {

    private UserServiceClient userServiceClient;
    private EmailNotificationClient email;
    private InvoiceNotifier notifier;

    private final UUID organizer = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        userServiceClient = mock(UserServiceClient.class);
        email = mock(EmailNotificationClient.class);
        notifier = new InvoiceNotifier(userServiceClient, email, "the-internal-token");
    }

    private EventInvoice invoice() {
        return EventInvoice.builder()
                .id(UUID.randomUUID())
                .invoiceNumber("INV-2026-000042")
                .organizerUuid(organizer)
                .periodStart(LocalDate.of(2026, 5, 1))
                .periodEnd(LocalDate.of(2026, 5, 31))
                .status(EventInvoice.InvoiceStatus.ISSUED)
                .currency("USD")
                .grossSales(new BigDecimal("450.00"))
                .commissionRate(new BigDecimal("10.0"))
                .commissionAmount(new BigDecimal("45.00"))
                .taxRate(new BigDecimal("15.0"))
                .taxAmount(new BigDecimal("6.75"))
                .totalAmount(new BigDecimal("51.75"))
                .issuedAt(LocalDateTime.of(2026, 6, 1, 1, 30))
                .dueAt(LocalDateTime.of(2026, 6, 15, 1, 30))
                .build();
    }

    @Test
    void emailsOrganizerWhenBusinessEmailResolves() {
        when(userServiceClient.lookupTenants(any(), eq("the-internal-token")))
                .thenReturn(ApiResult.ok("ok", List.of(
                        new TenantContactDTO(organizer, "Gala Events", "1 Main St", "billing@gala.co.zw"))));

        notifier.notifyIssued(invoice());

        verify(email).sendEmail(eq("billing@gala.co.zw"), contains("INV-2026-000042"),
                contains("Total due: USD 51.75"), contains("INVOICE-"));
    }

    @Test
    void noEmailWhenBusinessEmailMissing() {
        when(userServiceClient.lookupTenants(any(), anyString()))
                .thenReturn(ApiResult.ok("ok", List.of(
                        new TenantContactDTO(organizer, "Gala Events", "1 Main St", "  "))));

        notifier.notifyIssued(invoice());

        verify(email, never()).sendEmail(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void noEmailWhenLookupReturnsNullData() {
        when(userServiceClient.lookupTenants(any(), anyString()))
                .thenReturn(ApiResult.<List<TenantContactDTO>>builder().code("503").message("down").data(null).build());

        notifier.notifyIssued(invoice());

        verify(email, never()).sendEmail(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void swallowsEmailFailure_invoiceStillIssued() {
        when(userServiceClient.lookupTenants(any(), anyString()))
                .thenReturn(ApiResult.ok("ok", List.of(
                        new TenantContactDTO(organizer, "Gala Events", "1 Main St", "billing@gala.co.zw"))));
        doThrow(new NotificationDeliveryException("email gw down"))
                .when(email).sendEmail(anyString(), anyString(), anyString(), anyString());

        assertThatCode(() -> notifier.notifyIssued(invoice())).doesNotThrowAnyException();
    }

    @Test
    void swallowsLookupFailure_invoiceStillIssued() {
        when(userServiceClient.lookupTenants(any(), anyString()))
                .thenThrow(new RuntimeException("feign blew up"));

        assertThatCode(() -> notifier.notifyIssued(invoice())).doesNotThrowAnyException();
        verify(email, never()).sendEmail(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void dueSoon_emailsReminderWithDueDateAndDistinctReference() {
        when(userServiceClient.lookupTenants(any(), eq("the-internal-token")))
                .thenReturn(ApiResult.ok("ok", List.of(
                        new TenantContactDTO(organizer, "Gala Events", "1 Main St", "billing@gala.co.zw"))));

        notifier.notifyDueSoon(invoice());

        verify(email).sendEmail(eq("billing@gala.co.zw"),
                contains("due on 2026-06-15"), contains("INV-2026-000042"), contains("INV-DUE-"));
    }

    @Test
    void dueSoon_swallowsEmailFailure() {
        when(userServiceClient.lookupTenants(any(), anyString()))
                .thenReturn(ApiResult.ok("ok", List.of(
                        new TenantContactDTO(organizer, "Gala Events", "1 Main St", "billing@gala.co.zw"))));
        doThrow(new NotificationDeliveryException("email gw down"))
                .when(email).sendEmail(anyString(), anyString(), anyString(), anyString());

        assertThatCode(() -> notifier.notifyDueSoon(invoice())).doesNotThrowAnyException();
    }
}
