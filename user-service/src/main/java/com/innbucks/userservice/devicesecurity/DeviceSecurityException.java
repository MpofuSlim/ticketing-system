package com.innbucks.userservice.devicesecurity;

import org.springframework.http.HttpStatus;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A refusal on a device-security endpoint. Rendered by
 * {@code DeviceSecurityExceptionAdvice} as the standard envelope: the message is
 * written for the customer to read (neutral — it never names the check that
 * fired, contract §3 rule 6), {@code data.errorCode} is stable for the client to
 * branch on, and the rest of {@code data} carries what the contract asks each
 * status to carry (attemptsLeft on a wrong code, channel on a provider outage,
 * retryAfter on a rate limit).
 */
public class DeviceSecurityException extends RuntimeException {

    private final HttpStatus status;
    private final Map<String, Object> data;
    private final Long retryAfterSeconds;

    public DeviceSecurityException(HttpStatus status, String errorCode, String message,
                                   Map<String, Object> extra, Long retryAfterSeconds) {
        super(message);
        this.status = status;
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("errorCode", errorCode);
        if (extra != null) d.putAll(extra);
        if (retryAfterSeconds != null) d.put("retryAfter", retryAfterSeconds);
        this.data = d;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public DeviceSecurityException(HttpStatus status, String errorCode, String message) {
        this(status, errorCode, message, null, null);
    }

    public HttpStatus getStatus() {
        return status;
    }

    public Map<String, Object> getData() {
        return data;
    }

    public String getErrorCode() {
        return (String) data.get("errorCode");
    }

    public Long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }

    /** The feature is switched off on this cell: the endpoint does not exist. */
    public static DeviceSecurityException disabled() {
        return new DeviceSecurityException(HttpStatus.NOT_FOUND, "not_found", "Not found");
    }

    /** Anything temporary on our side. The app says "Please try again shortly" and never reads it as a refusal (§7.7). */
    public static DeviceSecurityException tryAgainShortly() {
        return new DeviceSecurityException(HttpStatus.SERVICE_UNAVAILABLE, "temporarily_unavailable",
                "We can't complete this right now. Please try again shortly.");
    }

    public static DeviceSecurityException badRequest(String field, String message) {
        return new DeviceSecurityException(HttpStatus.BAD_REQUEST, "invalid_request", message,
                field == null ? null : Map.of("field", field), null);
    }
}
