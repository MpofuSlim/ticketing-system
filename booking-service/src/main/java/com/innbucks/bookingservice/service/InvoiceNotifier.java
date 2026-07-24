package com.innbucks.bookingservice.service;

import com.innbucks.bookingservice.client.EmailNotificationClient;
import com.innbucks.bookingservice.client.UserServiceClient;
import com.innbucks.bookingservice.dto.ApiResult;
import com.innbucks.bookingservice.dto.TenantContactDTO;
import com.innbucks.bookingservice.dto.TenantLookupRequest;
import com.innbucks.bookingservice.entity.EventInvoice;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Emails an event organizer their freshly-issued commission invoice. The
 * organizer's email is their business email, resolved from user-service's
 * {@code /users/internal/tenants/lookup-by-uuid} (an organizer is a SwiftInn
 * system user, so the copy is SwiftInn-branded).
 *
 * <p>Entirely best-effort: any lookup/delivery failure is swallowed to a log
 * line — the invoice is already persisted and readable via the API, so a missing
 * email must never break (or roll back) generation. Plain text, matching the
 * SMS/WhatsApp standard.
 */
@Component
@Slf4j
public class InvoiceNotifier {

    private final UserServiceClient userServiceClient;
    private final EmailNotificationClient email;
    private final String internalToken;

    public InvoiceNotifier(UserServiceClient userServiceClient,
                           EmailNotificationClient email,
                           @Value("${innbucks.internal-api-token:}") String internalToken) {
        this.userServiceClient = userServiceClient;
        this.email = email;
        this.internalToken = internalToken;
    }

    public void notifyIssued(EventInvoice invoice) {
        try {
            String to = resolveOrganizerEmail(invoice.getOrganizerUuid());
            if (to == null || to.isBlank()) {
                log.info("No business email for organizer={}; invoice {} not emailed (still issued)",
                        invoice.getOrganizerUuid(), invoice.getInvoiceNumber());
                return;
            }
            email.sendEmail(to, subject(invoice), body(invoice), "INVOICE-" + invoice.getId());
            log.info("Invoice {} emailed to organizer={}", invoice.getInvoiceNumber(), invoice.getOrganizerUuid());
        } catch (RuntimeException ex) {
            log.warn("Failed emailing invoice {} (invoice still issued): {}",
                    invoice.getInvoiceNumber(), ex.getMessage());
        }
    }

    /**
     * Emails the organizer that a previously-issued invoice is now OVERDUE
     * (dunning). Same contract as {@link #notifyIssued}: strictly best-effort,
     * any failure is a log line — the status flip is already committed.
     */
    public void notifyOverdue(EventInvoice invoice) {
        try {
            String to = resolveOrganizerEmail(invoice.getOrganizerUuid());
            if (to == null || to.isBlank()) {
                log.info("No business email for organizer={}; overdue notice for {} not emailed",
                        invoice.getOrganizerUuid(), invoice.getInvoiceNumber());
                return;
            }
            email.sendEmail(to, overdueSubject(invoice), overdueBody(invoice),
                    "INV-OVD-" + invoice.getId());   // <=46-char API reference limit
            log.info("Overdue notice for invoice {} emailed to organizer={}",
                    invoice.getInvoiceNumber(), invoice.getOrganizerUuid());
        } catch (RuntimeException ex) {
            log.warn("Failed emailing overdue notice for invoice {} (invoice still OVERDUE): {}",
                    invoice.getInvoiceNumber(), ex.getMessage());
        }
    }

    /**
     * Emails the organizer that an ISSUED invoice is due within the next few
     * days — the friendly nudge BEFORE the overdue dunning. Same contract as
     * the siblings: strictly best-effort, any failure is a log line (the
     * due-soon marker was already stamped at claim time).
     */
    public void notifyDueSoon(EventInvoice invoice) {
        try {
            String to = resolveOrganizerEmail(invoice.getOrganizerUuid());
            if (to == null || to.isBlank()) {
                log.info("No business email for organizer={}; due-soon notice for {} not emailed",
                        invoice.getOrganizerUuid(), invoice.getInvoiceNumber());
                return;
            }
            email.sendEmail(to, dueSoonSubject(invoice), dueSoonBody(invoice),
                    "INV-DUE-" + invoice.getId());   // <=46-char API reference limit
            log.info("Due-soon notice for invoice {} emailed to organizer={}",
                    invoice.getInvoiceNumber(), invoice.getOrganizerUuid());
        } catch (RuntimeException ex) {
            log.warn("Failed emailing due-soon notice for invoice {} (marker already stamped): {}",
                    invoice.getInvoiceNumber(), ex.getMessage());
        }
    }

    private static String dueSoonSubject(EventInvoice i) {
        return "Reminder: SwiftInn commission invoice " + i.getInvoiceNumber()
                + " is due on " + i.getDueAt().toLocalDate();
    }

    private static String dueSoonBody(EventInvoice i) {
        return "Hi,\n\n"
                + "A friendly reminder that your SwiftInn commission invoice "
                + i.getInvoiceNumber() + " for " + i.getCurrency() + " "
                + i.getTotalAmount().toPlainString() + " is due on "
                + i.getDueAt().toLocalDate() + ".\n\n"
                + "Settling it before the due date keeps your account in good "
                + "standing. If you have already paid, please disregard this email.\n\n"
                + "Thank you,\nSwiftInn";
    }

    private static String overdueSubject(EventInvoice i) {
        return "Overdue: SwiftInn commission invoice " + i.getInvoiceNumber()
                + " (" + i.getCurrency() + " " + i.getTotalAmount().toPlainString() + ")";
    }

    private static String overdueBody(EventInvoice i) {
        return "Hi,\n\n"
                + "Your SwiftInn commission invoice " + i.getInvoiceNumber() + " for "
                + i.getCurrency() + " " + i.getTotalAmount().toPlainString()
                + " is now past its due date and has been marked overdue.\n\n"
                + "Please arrange payment at your earliest convenience, or contact "
                + "SwiftInn support if you believe this is in error or have already paid.\n\n"
                + "Thank you,\nSwiftInn";
    }

    private String resolveOrganizerEmail(UUID organizerUuid) {
        ApiResult<List<TenantContactDTO>> res =
                userServiceClient.lookupTenants(new TenantLookupRequest(List.of(organizerUuid)), internalToken);
        if (res == null || res.getData() == null) {
            return null;
        }
        return res.getData().stream()
                .filter(t -> organizerUuid.equals(t.userUuid()))
                .map(TenantContactDTO::businessEmail)
                .filter(e -> e != null && !e.isBlank())
                .findFirst()
                .orElse(null);
    }

    private static String subject(EventInvoice i) {
        return "Your SwiftInn commission invoice " + i.getInvoiceNumber()
                + " (" + i.getCurrency() + " " + i.getTotalAmount().toPlainString() + " due)";
    }

    private static String body(EventInvoice i) {
        String cur = i.getCurrency();
        return "Hi,\n\n"
                + "A new commission invoice has been issued for your ticket sales.\n\n"
                + "Invoice: " + i.getInvoiceNumber() + "\n"
                + "Period: " + i.getPeriodStart() + " to " + i.getPeriodEnd() + "\n"
                + "Ticket sales: " + cur + " " + i.getGrossSales().toPlainString() + "\n"
                + "Commission (" + i.getCommissionRate().toPlainString() + "%): "
                + cur + " " + i.getCommissionAmount().toPlainString() + "\n"
                + "VAT (" + i.getTaxRate().toPlainString() + "%): "
                + cur + " " + i.getTaxAmount().toPlainString() + "\n"
                + "Total due: " + cur + " " + i.getTotalAmount().toPlainString() + "\n"
                + "Due date: " + i.getDueAt().toLocalDate() + "\n\n"
                + "View the full per-event breakdown in your organizer dashboard.\n\n"
                + "— The SwiftInn Team";
    }
}
