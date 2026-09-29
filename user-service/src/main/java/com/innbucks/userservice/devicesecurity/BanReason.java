package com.innbucks.userservice.devicesecurity;

/**
 * Why a device is banned (contract §7.5, §8.5). Whether the customer can lift it
 * on *569# depends on the reason, and a few reasons ban the DEVICE for every
 * customer rather than one (customer, device) pair.
 */
public enum BanReason {
    /** Hooking framework, tampered app, automation, malware. USSD-unlockable. */
    INTEGRITY(true, false),
    /** The fraud desk's suspicion, or a fourth temporary block in 30 days. USSD-unlockable (desk bans after 24h). */
    FRAUD_SUSPECTED(true, false),
    /** One device signing in to too many accounts — the mule-farm pattern. Support only, every customer. */
    SHARED_DEVICE(false, true),
    /** Confirmed fraud. Support only, every customer. */
    CONFIRMED_FRAUD(false, true),
    /** The number's SIM was swapped recently. Support only. Reserved until the networks give us the signal. */
    SIM_SWAP(false, false),
    /** The customer blocked it themselves (lost or stolen phone), on *569# or through the call centre. USSD-unlockable. */
    CUSTOMER_REPORTED(true, false);

    private final boolean ussdUnlockable;
    private final boolean deviceWide;

    BanReason(boolean ussdUnlockable, boolean deviceWide) {
        this.ussdUnlockable = ussdUnlockable;
        this.deviceWide = deviceWide;
    }

    /** Whether *569# may lift a ban for this reason at all (other safeguards still apply). */
    public boolean ussdUnlockable() {
        return ussdUnlockable;
    }

    /** Whether the ban applies to the device for every customer (§8.5, last paragraph). */
    public boolean deviceWide() {
        return deviceWide;
    }
}
