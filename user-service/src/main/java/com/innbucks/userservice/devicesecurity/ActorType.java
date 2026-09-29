package com.innbucks.userservice.devicesecurity;

/** Who caused a device-security event. */
public enum ActorType {
    /** The super app, through the broker. */
    APP,
    /** The broker itself (login results, ticket redemption). */
    BROKER,
    /** The *569# USSD service, on behalf of the dialling customer. */
    USSD,
    /** A call-centre or fraud-desk agent in the admin portal. */
    SUPPORT,
    /** The signed-in customer (Your devices, in-session step-up). */
    CUSTOMER,
    /** DTX itself (rules, lazy expiry). */
    SYSTEM
}
