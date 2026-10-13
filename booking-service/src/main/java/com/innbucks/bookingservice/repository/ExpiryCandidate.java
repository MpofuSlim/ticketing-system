package com.innbucks.bookingservice.repository;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One row of the expiry sweep's candidate scan: the booking id and the key
 * its keyset cursor advances on. Read with no lock; the sweep locks each one
 * on its own afterwards (see {@code BookingExpirationService}).
 */
public record ExpiryCandidate(UUID id, LocalDateTime expiresAt) {
}
