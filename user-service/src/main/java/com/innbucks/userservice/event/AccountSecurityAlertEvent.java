package com.innbucks.userservice.event;

/**
 * Published when a security-sensitive change happens on an account — password
 * changed, password reset, or MFA turned on/off. Carries the contact fields so
 * the notifier never re-reads the user; {@code customer} drives the brand
 * (InnBucks for customers, SwiftInn for system users). Delivered on BOTH email
 * and SMS (a security event — reach the user everywhere) by
 * {@link com.innbucks.userservice.notification.AccountSecurityNotificationListener},
 * mirroring the account-locked alert.
 */
public record AccountSecurityAlertEvent(
        Long userId,
        String firstName,
        String email,
        String phoneNumber,
        boolean customer,
        Type type
) {
    public enum Type { PASSWORD_CHANGED, PASSWORD_RESET, MFA_ENABLED, MFA_DISABLED }
}
