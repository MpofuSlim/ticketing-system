package com.innbucks.userservice.client;

public class OradianClientException extends RuntimeException {
    public OradianClientException(String message) {
        super(message);
    }

    public OradianClientException(String message, Throwable cause) {
        super(message, cause);
    }
}
