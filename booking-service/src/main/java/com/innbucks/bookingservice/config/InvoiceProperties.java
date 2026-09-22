package com.innbucks.bookingservice.config;

import com.innbucks.bookingservice.entity.OrganizerBillingConfig.BillingCycle;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * Deployment-level invoicing terms (bound from {@code app.invoicing.*}). These
 * are the defaults applied to organizers without an
 * {@link com.innbucks.bookingservice.entity.OrganizerBillingConfig} override,
 * plus the VAT rate and payment-term that apply to every invoice in this cell.
 */
@Component
@ConfigurationProperties(prefix = "app.invoicing")
@Getter
@Setter
public class InvoiceProperties {

    /** Default platform commission, as a percentage (e.g. 10.0 = 10%). */
    private BigDecimal defaultCommissionRate = new BigDecimal("10.0");

    /** VAT applied to the commission, as a percentage (e.g. 15.0 = 15%). Snapshotted per invoice. */
    private BigDecimal vatRate = new BigDecimal("15.0");

    /** Default billing cycle for organizers without an override. */
    private BillingCycle defaultBillingCycle = BillingCycle.MONTHLY;

    /** Days from issue to due date. */
    private int dueDays = 14;

    /** Master switch for the periodic generation + overdue-sweep jobs. */
    private boolean schedulerEnabled = true;

    /**
     * Days to wait after a billing period closes before invoicing the events
     * that ended in it.
     *
     * <p>Organizers are billed after an event runs, but "ran" and "settled" are
     * not the same instant — a refund issued in the days after an event would
     * otherwise land behind an already-snapshotted invoice, leaving the
     * organizer billed commission on a ticket that no longer exists and no way
     * to correct it (a credit note is not modelled). The grace lets those
     * settle first.
     *
     * <p>Zero is legal and means "invoice as soon as the period closes", which
     * reinstates that race. Raising it only delays revenue; it never loses any,
     * because generation is idempotent on (organizer, period) and the scheduler
     * runs daily until the period is billed.
     */
    private int settleGraceDays = 7;
}
