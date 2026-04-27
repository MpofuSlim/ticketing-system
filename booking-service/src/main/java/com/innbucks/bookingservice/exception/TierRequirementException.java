package com.innbucks.bookingservice.exception;

import lombok.Getter;

// Thrown when an authenticated user's tier is too low for a tier-gated
// operation. Surfaced to clients as the envelope:
//   { code: "422", message: <reason>, data: { requiredTier, currentTier } }
@Getter
public class TierRequirementException extends RuntimeException {
    private final int requiredTier;
    private final int currentTier;

    public TierRequirementException(int requiredTier, int currentTier, String message) {
        super(message);
        this.requiredTier = requiredTier;
        this.currentTier = currentTier;
    }
}
