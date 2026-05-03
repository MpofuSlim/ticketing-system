package com.innbucks.loyaltyservice.exception;

public class InsufficientPointsException extends RuntimeException {
    public InsufficientPointsException(String message) {
        super(message);
    }
}
