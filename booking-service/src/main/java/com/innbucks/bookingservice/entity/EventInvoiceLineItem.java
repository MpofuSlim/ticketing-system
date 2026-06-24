package com.innbucks.bookingservice.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Per-event breakdown row of an {@link EventInvoice}: one row per event the
 * organizer earned CONFIRMED revenue on during the invoice's period. The line
 * commissions sum exactly to the invoice's {@code commissionAmount} (the
 * commission is rounded per line, then summed — see InvoiceService).
 */
@Entity
@Table(name = "event_invoice_line_items")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EventInvoiceLineItem {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "invoice_id", nullable = false)
    private EventInvoice invoice;

    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    @Column(name = "tickets_sold", nullable = false)
    private long ticketsSold;

    @Column(name = "confirmed_bookings", nullable = false)
    private long confirmedBookings;

    @Column(name = "gross_sales", nullable = false, precision = 19, scale = 2)
    private BigDecimal grossSales;

    @Column(name = "commission_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal commissionAmount;
}
