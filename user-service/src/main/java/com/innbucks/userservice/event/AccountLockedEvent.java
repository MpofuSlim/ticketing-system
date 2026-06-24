package com.innbucks.userservice.event;

import java.time.Instant;

/**
 * Published by AuthService when an account is locked after too many failed
 * sign-in attempts. Carries everything the notifier needs so it never re-reads
 * the user. {@code customer} drives the brand (InnBucks for customers, SwiftInn
 * for system users).
 */
public record AccountLockedEvent(
        Long userId,
        String firstName,
        String email,
        String phoneNumber,
        boolean customer,
        Instant lockedUntil
) {
}
