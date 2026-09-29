package com.innbucks.userservice.devicesecurity;

/**
 * The state of one (customer, device) pair (contract §4). DTX owns every
 * transition; the app only ever sees the resulting {@link Decision}.
 */
public enum DeviceState {
    /** Never seen for this customer. The next sign-in needs an OTP. */
    NEW,
    /** OTP done, PIN not yet proven. Becomes TRUSTED only on a broker-reported successful login. */
    PENDING_PIN,
    /** OTP and PIN done, trust not expired. Signs in with the PIN alone. */
    TRUSTED,
    /** Trusted before, but the next sign-in needs a fresh OTP. */
    STEP_UP,
    /** Stopped for a set time (or, with no end time, while an integrity check fails). */
    TEMP_BLOCKED,
    /** Stopped until the customer unlocks it on *569#, or support lifts it. */
    BANNED,
    /** The customer (or support) removed it. Signing in again starts at NEW. */
    REVOKED;

    /** States the device can still be blocked from (USSD "Block device", support block). */
    public boolean isBlockable() {
        return this != BANNED && this != REVOKED;
    }

    /** States in which the device is currently stopped. */
    public boolean isStopped() {
        return this == TEMP_BLOCKED || this == BANNED;
    }
}
