package com.innbucks.bookingservice.exception;

import com.innbucks.bookingservice.dto.ApiResult;
import com.innbucks.bookingservice.dto.TierViolationData;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.*;

class GlobalExceptionHandlerTest {

    @Test
    void handleTierRequirement_returns422WithStructuredEnvelope() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        String reason = "You require tier 2 registration to access that feature (current tier: 1)";

        ResponseEntity<ApiResult<TierViolationData>> response =
                handler.handleTierRequirement(new TierRequirementException(2, 1, reason));

        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, response.getStatusCode());
        ApiResult<TierViolationData> body = response.getBody();
        assertNotNull(body);
        assertEquals("422", body.getCode());
        assertEquals(reason, body.getMessage());
        assertNotNull(body.getData());
        assertEquals(2, body.getData().getRequiredTier());
        assertEquals(1, body.getData().getCurrentTier());
    }

    // ---- audit #9 — typed-exception status-code mapping ----

    @Test
    void handleNotFound_returns404WithMessage() {
        ResponseEntity<ApiResult<Void>> resp = new GlobalExceptionHandler()
                .handleNotFound(new NotFoundException("Seat xyz not found"));
        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode());
        assertNotNull(resp.getBody());
        assertEquals("Seat xyz not found", resp.getBody().getMessage());
    }

    @Test
    void handleBookingConflict_returns409WithMessage() {
        ResponseEntity<ApiResult<Void>> resp = new GlobalExceptionHandler()
                .handleBookingConflict(new BookingConflictException("Booking is already cancelled"));
        assertEquals(HttpStatus.CONFLICT, resp.getStatusCode());
        assertNotNull(resp.getBody());
        assertEquals("Booking is already cancelled", resp.getBody().getMessage());
    }

    @Test
    void handleBadRequest_returns400WithMessage() {
        ResponseEntity<ApiResult<Void>> resp = new GlobalExceptionHandler()
                .handleBadRequest(new BadRequestException("Category does not belong to event"));
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        assertNotNull(resp.getBody());
        assertEquals("Category does not belong to event", resp.getBody().getMessage());
    }

    @Test
    void handleDependencyUnavailable_returns503_withGenericMessage_doesNotLeakDependencyName() {
        // The raw exception text names the failing internal dependency
        // ("event-service down") — useful in logs, but it must NOT reach the
        // customer. The handler returns a generic 503 message instead; the
        // raw text is logged, not surfaced.
        ResponseEntity<ApiResult<Void>> resp = new GlobalExceptionHandler()
                .handleDependencyUnavailable(new DependencyUnavailableException("event-service down"));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, resp.getStatusCode());
        assertNotNull(resp.getBody());
        assertEquals("We're having trouble reaching part of the system right now. Please try again in a moment.",
                resp.getBody().getMessage());
        // Guard against a future regression that re-introduces the passthrough leak.
        org.junit.jupiter.api.Assertions.assertFalse(
                resp.getBody().getMessage().contains("event-service"),
                "503 body must not leak the internal dependency name");
    }

    @Test
    void handleResponseStatusException_honoursEmbeddedStatusAndReason_notSwallowedByRuntimeCatchAll() {
        // Regression for the production incident: a controller throwing
        // `new ResponseStatusException(HttpStatus.NOT_FOUND, "...")` was being
        // swallowed by the @ExceptionHandler(RuntimeException.class) catch-all
        // (ResponseStatusException extends RuntimeException) and surfaced as a
        // sanitised 500 — turning every deliberate 4xx throw into an opaque
        // server error in the log + a misleading client status.
        ResponseEntity<ApiResult<Void>> resp = new GlobalExceptionHandler()
                .handleResponseStatus(new ResponseStatusException(HttpStatus.NOT_FOUND, "Phone not registered"));
        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode());
        assertNotNull(resp.getBody());
        assertEquals("Phone not registered", resp.getBody().getMessage());
    }

    @Test
    void handleResponseStatusException_fallsBackToStatusReasonPhrase_whenReasonIsNull() {
        ResponseEntity<ApiResult<Void>> resp = new GlobalExceptionHandler()
                .handleResponseStatus(new ResponseStatusException(HttpStatus.CONFLICT));
        assertEquals(HttpStatus.CONFLICT, resp.getStatusCode());
        assertNotNull(resp.getBody());
        // Reason phrase from HttpStatus when ex.getReason() is null — never a
        // null body.message that would crash the FE's renderer.
        assertEquals("Conflict", resp.getBody().getMessage());
    }

    @Test
    void handleRuntime_returns500WithSanitisedMessage_andDoesNotLeakInternals() {
        // Audit #9 main fix: a bare RuntimeException (e.g. a DB integrity error
        // bubbling up) used to be returned to the client as 400 with the raw
        // exception message. Now it must:
        //   (a) return 500 (it's a server error, not a client error)
        //   (b) NOT leak the internal message — generic "unexpected error"
        ResponseEntity<ApiResult<Void>> resp = new GlobalExceptionHandler()
                .handleRuntime(new RuntimeException("ERROR: duplicate key value violates unique constraint \"users_email_key\""));
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, resp.getStatusCode());
        assertNotNull(resp.getBody());
        // Generic message — internal details NEVER reach the client.
        assertEquals("An unexpected error occurred. Please try again.", resp.getBody().getMessage());
        assertFalse(resp.getBody().getMessage().toLowerCase().contains("duplicate"));
        assertFalse(resp.getBody().getMessage().toLowerCase().contains("constraint"));
    }
}
