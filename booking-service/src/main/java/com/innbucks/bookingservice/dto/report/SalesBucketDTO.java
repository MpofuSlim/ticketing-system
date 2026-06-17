package com.innbucks.bookingservice.dto.report;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One bucket of the sales time-series — net revenue and tickets for CONFIRMED
 * bookings that originated within the bucket window. {@code bucketStart} is the
 * first day of the bucket (the day itself for DAY granularity, the Monday of
 * the ISO week for WEEK). Buckets with no sales are omitted.
 */
@Schema(name = "OrganizerSalesBucket",
        description = "A single day/week bucket of the organizer's sales time-series.")
public record SalesBucketDTO(
        @Schema(example = "2026-05-04") LocalDate bucketStart,
        @Schema(example = "18") long confirmedBookings,
        @Schema(example = "47") long ticketsSold,
        @Schema(example = "4700.00") BigDecimal revenue
) { }
