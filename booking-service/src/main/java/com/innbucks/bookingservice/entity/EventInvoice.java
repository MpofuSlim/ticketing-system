package com.innbucks.bookingservice.entity;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A platform commission invoice issued to one EVENT_ORGANIZER for one billing
 * period.
 *
 * <p>Generated from the organizer's CONFIRMED ticket revenue in
 * {@code [periodStart, periodEnd]} (inclusive days). The fee math, snapshotted
 * at generation:
 * <pre>
 *   grossSales        = sum of currently-CONFIRMED booking totals in the period
 *   commissionAmount  = round2( sum over events of grossSales(event) * commissionRate% )
 *   taxAmount         = round2( commissionAmount * taxRate% )
 *   totalAmount       = commissionAmount + taxAmount   (what the organizer owes)
 * </pre>
 *
 * <p>{@code commissionRate} and {@code taxRate} are stored on the row so a later
 * change to the organizer's terms (or the deployment VAT rate) never alters an
 * already-issued invoice. The {@code (organizerUuid, periodStart, periodEnd)}
 * unique key makes generation idempotent.
 */
@Entity
@Table(name = "event_invoices")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EventInvoice {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "invoice_number", nullable = false, unique = true, length = 40)
    private String invoiceNumber;

    @Column(name = "organizer_uuid", nullable = false)
    private UUID organizerUuid;

    @Column(name = "period_start", nullable = false)
    private LocalDate periodStart;

    /** Inclusive last day of the billing period. */
    @Column(name = "period_end", nullable = false)
    private LocalDate periodEnd;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private InvoiceStatus status;

    @Column(nullable = false, length = 8)
    private String currency;

    @Column(name = "confirmed_bookings", nullable = false)
    private long confirmedBookings;

    @Column(name = "tickets_sold", nullable = false)
    private long ticketsSold;

    /** Net confirmed ticket revenue the commission is charged on. */
    @Column(name = "gross_sales", nullable = false, precision = 19, scale = 2)
    private BigDecimal grossSales;

    @Column(name = "commission_rate", nullable = false, precision = 7, scale = 4)
    private BigDecimal commissionRate;

    /** The fee (commission) — invoice subtotal before tax. */
    @Column(name = "commission_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal commissionAmount;

    @Column(name = "tax_rate", nullable = false, precision = 7, scale = 4)
    private BigDecimal taxRate;

    @Column(name = "tax_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal taxAmount;

    /** commissionAmount + taxAmount — the amount the organizer owes the platform. */
    @Column(name = "total_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal totalAmount;

    @Column(name = "issued_at", nullable = false)
    private LocalDateTime issuedAt;

    @Column(name = "due_at", nullable = false)
    private LocalDateTime dueAt;

    @Column(name = "paid_at")
    private LocalDateTime paidAt;

    @Column(name = "cancelled_at")
    private LocalDateTime cancelledAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @OneToMany(mappedBy = "invoice", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("grossSales DESC")
    @Builder.Default
    private List<EventInvoiceLineItem> lineItems = new ArrayList<>();

    @PrePersist
    void onCreate() {
        this.createdAt = LocalDateTime.now(ZoneOffset.UTC);
        if (this.status == null) {
            this.status = InvoiceStatus.ISSUED;
        }
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = LocalDateTime.now(ZoneOffset.UTC);
    }

    /** Attach a line item, keeping both sides of the relationship consistent. */
    public void addLineItem(EventInvoiceLineItem item) {
        item.setInvoice(this);
        this.lineItems.add(item);
    }

    /**
     * Invoice lifecycle. Generation creates an invoice {@code ISSUED}; an admin
     * settles it ({@code PAID}) or voids it ({@code CANCELLED}); the overdue
     * sweep flips an unpaid past-due {@code ISSUED} to {@code OVERDUE}.
     */
    public enum InvoiceStatus {
        ISSUED,
        PAID,
        OVERDUE,
        CANCELLED
    }
}
