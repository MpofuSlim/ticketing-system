package com.innbucks.userservice.devicesecurity;

/**
 * The one field the app branches on (contract §7.1, FIRM). Never the message,
 * never the reason.
 */
public enum Decision {
    TOKEN,
    OTP_REQUIRED,
    TEMP_BLOCKED,
    BANNED
}
