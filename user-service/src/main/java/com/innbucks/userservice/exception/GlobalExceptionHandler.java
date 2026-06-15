package com.innbucks.userservice.exception;

import com.innbucks.userservice.cells.WrongCellException;
import com.innbucks.userservice.client.NotificationDeliveryException;
import com.innbucks.userservice.client.OradianClientException;
import com.innbucks.userservice.dto.ApiResult;
import com.innbucks.userservice.service.AuthService;
import com.innbucks.userservice.service.RefreshTokenService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    // Let Spring Security's entry point / access-denied handler produce the 401/403
    // envelopes instead of being swallowed as a generic 400.
    @ExceptionHandler(AuthenticationException.class)
    public void handleAuthentication(AuthenticationException ex) throws AuthenticationException {
        throw ex;
    }

    @ExceptionHandler(AccessDeniedException.class)
    public void handleAccessDenied(AccessDeniedException ex) throws AccessDeniedException {
        throw ex;
    }

    /**
     * @Valid bean-validation failures on @RequestBody. Surfaces field-level
     * messages in data.fields so the frontend can highlight the specific bad
     * input without parsing a free-form string. Without this handler the
     * request fell through to Spring's DefaultHandlerExceptionResolver which
     * returned a cryptic "Validation failed for object='customerTier2RegisterDTO'"
     * with no per-field detail — useless to the user.
     *
     * <p>{@code data} is a map keyed by field name, value is the
     * {@code @NotBlank(message=…)} / {@code @Size(message=…)} text from the
     * DTO. Multiple violations on the same field collapse to the first one
     * (showing all of them tends to confuse rather than help).
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResult<Map<String, String>>> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, String> fields = new LinkedHashMap<>();
        for (var fe : ex.getBindingResult().getFieldErrors()) {
            fields.putIfAbsent(fe.getField(),
                    fe.getDefaultMessage() == null ? "Invalid value" : fe.getDefaultMessage());
        }
        log.warn("Validation failed fields={}", fields);
        return ResponseEntity.badRequest().body(ApiResult.<Map<String, String>>builder()
                .code("400 BAD_REQUEST")
                .message("Validation failed")
                .data(fields)
                .build());
    }

    // Surface Oradian middleware failures as 502 Bad Gateway so the client knows
    // the local state was rolled back — distinct from a 400 caused by user input.
    // The raw upstream text often contains internal HTTP envelopes / Oradian
    // operation names; it goes to logs, the user sees a friendly retry hint.
    @ExceptionHandler(OradianClientException.class)
    public ResponseEntity<ApiResult<Void>> handleOradian(OradianClientException ex) {
        log.warn("Oradian middleware call failed: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(ApiResult.error(HttpStatus.BAD_GATEWAY,
                        "We're having trouble reaching the bank right now. Please try again in a moment."));
    }

    // WhatsApp / SMS notification gateway failures (OTP / approval delivery).
    // 502 so the client knows delivery failed and a rolled-back request can be
    // retried. Raw gateway text (e.g. "msisdn rejected by provider") goes to
    // logs only — the customer sees a generic retry hint.
    @ExceptionHandler(NotificationDeliveryException.class)
    public ResponseEntity<ApiResult<Void>> handleNotificationDelivery(NotificationDeliveryException ex) {
        log.warn("Notification delivery failed: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(ApiResult.error(HttpStatus.BAD_GATEWAY,
                        "We couldn't send your verification message. Please try again in a moment."));
    }

    // Domain "no such resource" surface — must come BEFORE the
    // RuntimeException fallback so a typed miss surfaces as 404 instead
    // of being demoted to 400 by the catch-all. Use NotFoundException in
    // service-layer findById().orElseThrow(...) calls.
    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ApiResult<Void>> handleNotFound(NotFoundException ex) {
        log.info("Not found: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResult.error(HttpStatus.NOT_FOUND, ex.getMessage()));
    }

    /**
     * Spring's {@link ResponseStatusException} extends RuntimeException, so
     * without this handler it would be swallowed by the {@code RuntimeException}
     * catch-all below — which downgrades status to 400 and reflects whatever
     * raw text the throw site picked. Honour the embedded status / reason
     * instead. {@code ShopStaffService} still throws ResponseStatusException
     * for 401/403/400 — those now reach the client correctly. Prefer the
     * typed {@link NotFoundException} at new throw sites.
     */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiResult<Void>> handleResponseStatus(ResponseStatusException ex) {
        HttpStatus status = HttpStatus.resolve(ex.getStatusCode().value());
        if (status == null) status = HttpStatus.INTERNAL_SERVER_ERROR;
        String reason = ex.getReason() == null ? status.getReasonPhrase() : ex.getReason();
        log.warn("ResponseStatusException status={} reason={}", status.value(), reason);
        return ResponseEntity.status(status).body(ApiResult.error(status, reason));
    }

    // Wrong-identifier / wrong-password / locked-out outcomes all surface as
    // InvalidCredentialsException. The exception's own message ("Invalid
    // credentials") is intentional, deliberately indistinguishable copy —
    // it must NOT be collapsed into the generic RuntimeException fallback
    // (which would leak nothing but also stop telling the FE WHY the login
    // failed, breaking the customer-visible auth flow). Passthrough of
    // ex.getMessage() is safe here because the message is a typed constant
    // baked into the exception, not raw upstream text.
    @ExceptionHandler(AuthService.InvalidCredentialsException.class)
    public ResponseEntity<ApiResult<Void>> handleInvalidCredentials(AuthService.InvalidCredentialsException ex) {
        log.info("Invalid credentials");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResult.error(HttpStatus.BAD_REQUEST, ex.getMessage()));
    }

    // Refresh-token reuse: a previously-rotated-out token was replayed, so
    // the whole family was revoked. The message ("Refresh token reuse
    // detected; family revoked") is a typed constant from RefreshTokenService
    // — safe to passthrough, and the FE needs the "reuse detected" wording
    // to route the user back to the login screen rather than silently retry.
    @ExceptionHandler(RefreshTokenService.ReuseDetectedException.class)
    public ResponseEntity<ApiResult<Void>> handleReuseDetected(RefreshTokenService.ReuseDetectedException ex) {
        log.warn("Refresh-token reuse detected: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResult.error(HttpStatus.BAD_REQUEST, ex.getMessage()));
    }

    // Catch-all for any RuntimeException not handled by a more specific
    // @ExceptionHandler above. Raw ex.getMessage() can carry internal text
    // (table names, stack-trace fragments, library jargon), so it goes to
    // logs only — the customer sees a generic message. Prefer adding a typed
    // exception + dedicated handler over relying on this fallback.
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<ApiResult<Void>> handleRuntime(RuntimeException ex) {
        log.warn("RuntimeException ({}): {}", ex.getClass().getSimpleName(), ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResult.error(HttpStatus.BAD_REQUEST,
                        "We couldn't process your request. Please try again."));
    }

    /**
     * Wrong-cell redirect. Carries the home country and (when the registry
     * knows it) the home cell's public base URL so the FE can switch base
     * URL and retry without an extra lookup round-trip.
     */
    @ExceptionHandler(WrongCellException.class)
    public ResponseEntity<ApiResult<Map<String, String>>> handleWrongCell(WrongCellException ex) {
        Map<String, String> data = new LinkedHashMap<>();
        data.put("errorCode", "wrong_cell");
        data.put("homeCountry", ex.getHomeCountry());
        data.put("homeBaseUrl", ex.getHomeBaseUrl());
        log.info("WrongCellException homeCountry={} homeBaseUrl={}",
                ex.getHomeCountry(), ex.getHomeBaseUrl() == null ? "<unknown>" : ex.getHomeBaseUrl());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(
                ApiResult.of(HttpStatus.CONFLICT, ex.getMessage(), data));
    }
}
