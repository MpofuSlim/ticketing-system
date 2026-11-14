package com.innbucks.userservice.exception;

import org.springframework.http.HttpStatus;

import java.util.Map;

/**
 * A refusal on the Organizer app's gate sign-in ({@code /auth/gate/**}, V57). A
 * {@link StaffPolicyException}, so {@code GlobalExceptionHandler} renders it as
 * {@code {"code", "message", "data": {"errorCode"}}}. Never a 401: the app's
 * session interceptor reads a 401 as "refresh me", and there is no session yet.
 *
 * <p>No answer here ever says whether a number is on a gate team: a wrong code,
 * an expired one and a code never sent (the number is on no team) are the same
 * {@link #CODE_INVALID}.
 */
public class GateSignInException extends StaffPolicyException {

    public static final String INVALID_PHONE = "invalid_phone";
    public static final String INVALID_PHONE_MESSAGE = "Enter a valid phone number.";

    public static final String CODE_INVALID = "code_invalid";
    public static final String CODE_INVALID_MESSAGE = "That code is wrong or has expired. Ask for a new one.";

    public static final String UNAVAILABLE = "gate_sign_in_unavailable";
    public static final String UNAVAILABLE_MESSAGE = "Gate sign-in isn't available right now. Try again in a minute.";

    public GateSignInException(HttpStatus status, String errorCode, String message) {
        super(status, errorCode, message, Map.of());
    }

    public static GateSignInException invalidPhone() {
        return new GateSignInException(HttpStatus.BAD_REQUEST, INVALID_PHONE, INVALID_PHONE_MESSAGE);
    }

    public static GateSignInException codeInvalid() {
        return new GateSignInException(HttpStatus.BAD_REQUEST, CODE_INVALID, CODE_INVALID_MESSAGE);
    }

    public static GateSignInException unavailable() {
        return new GateSignInException(HttpStatus.SERVICE_UNAVAILABLE, UNAVAILABLE, UNAVAILABLE_MESSAGE);
    }
}
