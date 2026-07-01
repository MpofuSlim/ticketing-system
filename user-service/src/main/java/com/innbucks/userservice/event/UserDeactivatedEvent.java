package com.innbucks.userservice.event;

/**
 * Published by {@code UserAdminService} after an account is deactivated
 * (SUPER_ADMIN {@code PUT /admin/users/{id}/active} with {@code active=false}).
 * The async listener ({@code AccountSecurityNotificationListener}) tells the
 * user off the request thread, so a slow notification gateway can't stall — or
 * time out — the deactivate response after the row has already committed.
 * {@code customer} drives the brand (InnBucks for customers, SwiftInn for
 * system users). Carries the contact fields so the listener never re-reads the
 * user.
 */
public record UserDeactivatedEvent(
        Long userId,
        String firstName,
        String email,
        String phoneNumber,
        boolean customer
) {
}
