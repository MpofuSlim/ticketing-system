package com.innbucks.userservice.devicesecurity;

/** Why a device is temporarily blocked (contract §7.4). Never shown to the customer. */
public enum BlockReason {
    OTP_ATTEMPTS,
    VELOCITY,
    WRONG_PINS,
    RISK,
    /** Root/jailbreak: no timer, lifts at the first sign-in where the check passes. */
    INTEGRITY_HOLD,
    /** Placed by the call center from the admin portal. */
    SUPPORT
}
