package com.innbucks.userservice.devicesecurity;

/** Lifecycle of one OTP challenge. Only OPEN accepts a send or a verify. */
public enum ChallengeStatus {
    OPEN,
    VERIFIED,
    /** Attempts used up. */
    DEAD,
    /** Voided by a block, ban, removal or the call center. */
    VOID
}
