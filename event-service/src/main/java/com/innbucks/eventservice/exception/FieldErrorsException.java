package com.innbucks.eventservice.exception;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A 400 that names each problem by its place in the request, in the same
 * shape bean validation answers with: {@code "Validation failed"} and
 * {@code data} = field path to message. For checks a {@code @Valid} cannot
 * express (the booking-questions list, V16).
 */
public class FieldErrorsException extends RuntimeException {

    private final Map<String, String> errors;

    public FieldErrorsException(Map<String, String> errors) {
        super("Validation failed");
        this.errors = Collections.unmodifiableMap(new LinkedHashMap<>(errors));
    }

    public Map<String, String> getErrors() {
        return errors;
    }
}
