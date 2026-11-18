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
     * because the scheduler bills every period whose grace has passed (see
     * {@link #catchUpDays}), whatever the grace is and whatever the cycle.
     */
    private int settleGraceDays = 7;

    /**
     * How many days the scheduler keeps trying a closed period, once a day,
     * after its settle grace ends. Values below 1 count as 1.
     *
     * <p>A period that cannot be billed on its first day (event-service
     * unreachable, a run missed during a deploy) is billed on a later run
     * inside this window; past it, it is never billed by the scheduler. 28
     * days keeps the last four weekly periods and one monthly period in each
     * run. Raising it widens how far back a commission rate set today can
     * reach into periods that were not invoiced at the old rate.
     */
    private int catchUpDays = 28;

    /**
     * The first day commission is billed on this deployment ({@code yyyy-MM-dd},
     * UTC): the scheduler never invoices an event that ended before it, and a
     * period that began earlier is invoiced from this day.
     *
     * <p>Blank (the default) or not a date: the scheduler invoices nothing and
     * logs an ERROR at startup and on every run. That is deliberate (owner
     * decision, 2026-11-18: bill forward only). Without a floor, switching the
     * scheduler on would invoice every period still in the catch-up window, at
     * the rate in force that day, for weeks in which commission was off, and
     * email each organizer the invoice. Set it to the day commission resumes.
     *
     * <p>Read as text and parsed by the scheduler, so a typo stops invoicing
     * rather than booking-service starting up.
     */
    private String billingStartDate = "";
}
