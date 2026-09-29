package com.innbucks.userservice.devicesecurity;

/** How staging's user login went, as the broker reports it (contract §5.4). */
public enum LoginOutcome {
    SUCCESS,
    WRONG_PIN,
    LOCKED,
    PIN_NOT_SET,
    ERROR
}
