package com.innbucks.bookingservice.repository.projection;

import com.innbucks.bookingservice.entity.EventInvoice;

import java.math.BigDecimal;

/** Per-status count + total-billed, for the admin invoice dashboard summary. */
public interface InvoiceStatusAggregate {
    EventInvoice.InvoiceStatus getStatus();

    long getCount();

    BigDecimal getTotal();
}
