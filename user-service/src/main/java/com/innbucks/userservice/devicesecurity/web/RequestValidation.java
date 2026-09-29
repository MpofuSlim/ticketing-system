package com.innbucks.userservice.devicesecurity.web;

import com.innbucks.userservice.devicesecurity.DeviceSecurityException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Bean validation run AFTER the partner-key check rather than by {@code @Valid}
 * (which Spring runs before the handler body). Otherwise an unauthenticated
 * prober would get field-level validation detail — and a switched-off cell
 * would answer an empty body with 400 instead of the 404 that says nothing.
 */
@Component
public class RequestValidation {

    private final Validator validator;

    public RequestValidation(Validator validator) {
        this.validator = validator;
    }

    public void check(Object body) {
        if (body == null) {
            throw DeviceSecurityException.badRequest(null, "A request body is required.");
        }
        Set<ConstraintViolation<Object>> violations = validator.validate(body);
        if (violations.isEmpty()) return;
        Map<String, String> fields = new LinkedHashMap<>();
        violations.stream()
                .sorted(Comparator.comparing(v -> v.getPropertyPath().toString()))
                .forEach(v -> fields.putIfAbsent(v.getPropertyPath().toString(), v.getMessage()));
        String first = fields.keySet().iterator().next();
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("field", first);
        extra.put("fields", fields);
        throw new DeviceSecurityException(HttpStatus.BAD_REQUEST, "validation_failed", fields.get(first), extra, null);
    }
}
