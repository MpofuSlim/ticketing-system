package com.innbucks.eventservice.exception;

import org.springframework.http.HttpStatus;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A refusal that carries a stable, machine-readable {@code errorCode} next to
 * the human message. Rendered by {@link GlobalExceptionHandler} as
 *
 * <pre>{"code":"&lt;status&gt; &lt;NAME&gt;","message":"…","data":{"errorCode":"…", …extra}}</pre>
 *
 * <p>The fleet shape (booking-service's class of the same name, user-service's
 * {@code StaffPolicyException}): {@code code} stays the status pair, the domain
 * code goes in {@code data.errorCode}, which is what a client branches on. The
 * typed exceptions beside it ({@link ConflictException} and friends) answer
 * {@code data: null}, so a refusal a client must tell apart from its siblings
 * uses this one.
 *
 * <p>{@code extra} adds fields beside {@code errorCode}. It may not carry its
 * own {@code errorCode}: the constructor refuses one, so the body can never
 * disagree with {@link #getErrorCode()}.
 */
public class ApiProblemException extends RuntimeException {

    private final HttpStatus status;
    private final String errorCode;
    private final Map<String, Object> extra;

    public ApiProblemException(HttpStatus status, String errorCode, String message,
                               Map<String, Object> extra) {
        super(message);
        if (status == null || errorCode == null || errorCode.isBlank()) {
            throw new IllegalArgumentException("status and errorCode are required");
        }
        if (extra != null && extra.containsKey("errorCode")) {
            throw new IllegalArgumentException("extra must not carry its own errorCode");
        }
        this.status = status;
        this.errorCode = errorCode;
        this.extra = extra == null || extra.isEmpty()
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(extra));
    }

    public ApiProblemException(HttpStatus status, String errorCode, String message) {
        this(status, errorCode, message, Map.of());
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public Map<String, Object> getExtra() {
        return extra;
    }

    /** {@code data} as rendered: {@code errorCode} first, then {@code extra} in its own order. */
    public Map<String, Object> data() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("errorCode", errorCode);
        data.putAll(extra);
        return data;
    }
}
