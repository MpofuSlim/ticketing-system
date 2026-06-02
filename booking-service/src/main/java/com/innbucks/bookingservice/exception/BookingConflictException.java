package com.innbucks.bookingservice.exception;

/**
 * Request is rejected because the booking (or a seat it touches) is in a
 * state that disallows the requested transition — already CANCELLED,
 * already CONFIRMED, hold expired, the requested seat is already booked
 * by someone else, etc. Maps to HTTP 409 Conflict.
 *
 * <p>Distinct from {@link BadRequestException} (400) which signals a
 * structurally wrong request — values out of range, missing fields — that
 * the client controls. A conflict means the request was well-formed but
 * the server's current state can't satisfy it; retrying without changing
 * state will keep failing.
 */
public class BookingConflictException extends RuntimeException {
    public BookingConflictException(String message) {
        super(message);
    }
}
