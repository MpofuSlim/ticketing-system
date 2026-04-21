package com.innbucks.bookingservice.idempotency;

/** Snapshot of a response produced for a given Idempotency-Key. */
public record StoredResponse(int status, String contentType, byte[] body) {
}
