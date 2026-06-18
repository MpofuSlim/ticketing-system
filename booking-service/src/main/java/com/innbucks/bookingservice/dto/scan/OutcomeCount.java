package com.innbucks.bookingservice.dto.scan;

import com.innbucks.bookingservice.entity.ScanAttempt;

/**
 * JPA constructor-expression target for the {@code GROUP BY outcome} queries
 * on {@code scan_attempts}. The fully-qualified class name in the JPQL
 * {@code SELECT new ...} clause MUST match this type — moving or renaming
 * the record will break the queries at startup.
 *
 * <p>{@code outcome} is the JPA-mapped {@link ScanAttempt.Outcome} enum
 * (Hibernate returns the enum value, not a String). The service layer
 * stringifies it before sending to the FE.
 */
public record OutcomeCount(
        ScanAttempt.Outcome outcome,
        Long count
) { }
