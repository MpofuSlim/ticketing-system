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
}
