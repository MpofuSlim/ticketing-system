package com.innbucks.userservice.devicesecurity;

/**
 * Why an OTP is being asked for (contract §7.3). For logs and support only: the
 * app shows one neutral line for every value.
 */
public enum OtpReason {
    NEW_DEVICE,
    DEVICE_CHANGED,
    TRUST_EXPIRED,
    LOCATION_ANOMALY,
    RISK,
    UNLOCKED,
    PIN_ISSUE,
    POLICY
}
