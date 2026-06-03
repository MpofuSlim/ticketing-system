package com.innbucks.eventservice.exception;

import com.innbucks.eventservice.dto.ApiResult;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void optimisticLockFailure_returns409Conflict() {
        ObjectOptimisticLockingFailureException ex = new ObjectOptimisticLockingFailureException(
                "com.innbucks.eventservice.entity.Event", "id-123");

        ResponseEntity<ApiResult<Void>> response = handler.handleOptimisticLock(ex);

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        ApiResult<Void> body = response.getBody();
        assertNotNull(body);
        assertEquals("409 CONFLICT", body.getCode());
        assertEquals("Event was modified by another request. Please refetch and retry.", body.getMessage());
        assertNull(body.getData());
    }

    // ---- typed-exception status-code mapping (matches booking-service #149) ----

    @Test
    void handleNotFound_returns404WithMessage() {
        ResponseEntity<ApiResult<Void>> resp = handler.handleNotFound(new NotFoundException("Event not found"));
        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode());
        assertNotNull(resp.getBody());
        assertEquals("Event not found", resp.getBody().getMessage());
    }

    @Test
    void handleBadRequest_returns400WithMessage() {
        ResponseEntity<ApiResult<Void>> resp = handler.handleBadRequest(
                new BadRequestException("count must be positive"));
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        assertNotNull(resp.getBody());
        assertEquals("count must be positive", resp.getBody().getMessage());
    }

    @Test
    void handleConflict_returns409WithMessage() {
        ResponseEntity<ApiResult<Void>> resp = handler.handleConflict(
                new ConflictException("Insufficient availability"));
        assertEquals(HttpStatus.CONFLICT, resp.getStatusCode());
        assertNotNull(resp.getBody());
        assertEquals("Insufficient availability", resp.getBody().getMessage());
    }

    @Test
    void handleForbidden_returns403WithMessage() {
        ResponseEntity<ApiResult<Void>> resp = handler.handleForbidden(
                new ForbiddenException("You are not authorized to update this event"));
        assertEquals(HttpStatus.FORBIDDEN, resp.getStatusCode());
        assertNotNull(resp.getBody());
        assertEquals("You are not authorized to update this event", resp.getBody().getMessage());
    }

    @Test
    void handleResponseStatusException_honoursEmbeddedStatus_notSwallowedByRuntimeCatchAll() {
        // ResponseStatusException extends RuntimeException, so without the
        // dedicated handler the RuntimeException catch-all swallows it and
        // surfaces every deliberate 4xx throw as a sanitised 500.
        ResponseEntity<ApiResult<Void>> resp = handler.handleResponseStatus(
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Event not found"));
        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode());
        assertEquals("Event not found", resp.getBody().getMessage());
    }

    @Test
    void handleRuntime_returns500WithSanitisedMessage_andDoesNotLeakInternals() {
        // Pre-refactor a bare RuntimeException (e.g. a DataIntegrityViolation
        // bubbling up) was returned to the client as 400 with the raw exception
        // message. After: 500 with a generic message. The string-sniffing
        // "contains 'not found' → 404" branch is also gone — the typed
        // NotFoundException covers that path explicitly.
        ResponseEntity<ApiResult<Void>> resp = handler.handleRuntime(
                new RuntimeException("ERROR: duplicate key value violates unique constraint \"events_pkey\""));
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, resp.getStatusCode());
        assertNotNull(resp.getBody());
        assertEquals("An unexpected error occurred. Please try again.", resp.getBody().getMessage());
        assertFalse(resp.getBody().getMessage().toLowerCase().contains("duplicate"));
        assertFalse(resp.getBody().getMessage().toLowerCase().contains("constraint"));
    }

    @Test
    void handleRuntime_neverUpgradesNotFoundStringToNotFound() {
        // The old handler used to map any message containing "not found"
        // (case-insensitive) to a 404. Pin that this is gone — only typed
        // NotFoundException maps to 404 now.
        ResponseEntity<ApiResult<Void>> resp = handler.handleRuntime(
                new RuntimeException("Something not found in cache"));
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, resp.getStatusCode());
    }
}
