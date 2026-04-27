package com.innbucks.bookingservice.exception;

// Thrown when an authenticated user's tier is too low for a tier-gated
// operation. Surfaced to clients as the custom envelope:
//   { code: "Do not meet min tier requirement", message: null, data: <reason> }
public class TierRequirementException extends RuntimeException {
    public TierRequirementException(String message) {
        super(message);
    }
}
