package com.innbucks.bookingservice.dto.report;

/** Granularity for the organizer sales time-series. */
public enum BucketSize {
    /** One bucket per calendar day (UTC). */
    DAY,
    /** One bucket per ISO week, keyed on the Monday of that week (UTC). */
    WEEK
}
