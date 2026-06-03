package com.innbucks.seatservice.exception;

import com.innbucks.seatservice.dto.ApiResult;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.server.ResponseStatusException;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void optimisticLockFailure_returns409Conflict() {
        ObjectOptimisticLockingFailureException ex = new ObjectOptimisticLockingFailureException(
                "com.innbucks.seatservice.entity.Seat", "id-123");

        ResponseEntity<ApiResult<Void>> response = handler.handleOptimisticLock(ex);

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        ApiResult<Void> body = response.getBody();
        assertNotNull(body);
        assertEquals("409 CONFLICT", body.getCode());
        assertEquals("Seat was modified by another request. Please retry.", body.getMessage());
        assertNull(body.getData());
    }

    // ---- typed-exception status-code mapping (matches booking-service #149) ----

    @Test
    void handleNotFound_returns404WithMessage() {
        ResponseEntity<ApiResult<Void>> resp = handler.handleNotFound(new NotFoundException("Seat not found"));
        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode());
        assertNotNull(resp.getBody());
        assertEquals("Seat not found", resp.getBody().getMessage());
    }

    @Test
    void handleBadRequest_returns400WithMessage() {
        ResponseEntity<ApiResult<Void>> resp = handler.handleBadRequest(
                new BadRequestException("Duplicate section 'A' in request"));
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        assertNotNull(resp.getBody());
        assertEquals("Duplicate section 'A' in request", resp.getBody().getMessage());
    }

    @Test
    void handleConflict_returns409WithMessage() {
        ResponseEntity<ApiResult<Void>> resp = handler.handleConflict(
                new ConflictException("Seat A1 is not available"));
        assertEquals(HttpStatus.CONFLICT, resp.getStatusCode());
        assertNotNull(resp.getBody());
        assertEquals("Seat A1 is not available", resp.getBody().getMessage());
    }

    @Test
    void handleForbidden_returns403WithMessage() {
        ResponseEntity<ApiResult<Void>> resp = handler.handleForbidden(
                new ForbiddenException("You cannot release a lock that belongs to another user"));
        assertEquals(HttpStatus.FORBIDDEN, resp.getStatusCode());
        assertNotNull(resp.getBody());
        assertEquals("You cannot release a lock that belongs to another user", resp.getBody().getMessage());
    }

    @Test
    void handleConstraintViolation_returns400WithViolationMessage_notSwallowedAs500() {
        // @Validated + @Min/@Max on @RequestParam fires a ConstraintViolationException
        // when a client sends size=999999. Without the dedicated handler it falls to
        // the RuntimeException catch-all and surfaces as a sanitised 500 — wrong status.
        ConstraintViolation<?> violation = Mockito.mock(ConstraintViolation.class);
        jakarta.validation.Path path = Mockito.mock(jakarta.validation.Path.class);
        Mockito.when(path.toString()).thenReturn("listBookings.size");
        Mockito.when(violation.getPropertyPath()).thenReturn(path);
        Mockito.when(violation.getMessage()).thenReturn("must be less than or equal to 100");

        ResponseEntity<ApiResult<Void>> resp = handler.handleConstraintViolation(
                new ConstraintViolationException(Set.of(violation)));
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        assertNotNull(resp.getBody());
        assertEquals("listBookings.size must be less than or equal to 100", resp.getBody().getMessage());
    }

    @Test
    void handleConstraintViolation_emptyViolations_returnsGenericMessage() {
        // Defensive: an empty violation set (shouldn't happen) must still yield a
        // sensible 400 — not null body.message that would crash an FE renderer.
        ResponseEntity<ApiResult<Void>> resp = handler.handleConstraintViolation(
                new ConstraintViolationException(Set.of()));
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        assertNotNull(resp.getBody());
        assertEquals("Invalid request parameter", resp.getBody().getMessage());
    }

    @Test
    void handleResponseStatusException_honoursEmbeddedStatus_notSwallowedByRuntimeCatchAll() {
        // ResponseStatusException extends RuntimeException; without the
        // dedicated handler the RuntimeException catch-all swallows it as 500.
        ResponseEntity<ApiResult<Void>> resp = handler.handleResponseStatus(
                new ResponseStatusException(HttpStatus.CONFLICT, "Seat A1 not available"));
        assertEquals(HttpStatus.CONFLICT, resp.getStatusCode());
        assertEquals("Seat A1 not available", resp.getBody().getMessage());
    }

    @Test
    void handleRuntime_returns500WithSanitisedMessage_andDoesNotLeakInternals() {
        // Pre-refactor a bare RuntimeException was returned to the client as
        // 400 with the raw exception message — and "not found" anywhere in the
        // message was sniffed for an implicit upgrade to 404. Both behaviours
        // are gone: typed exceptions cover the 4xx cases; the catch-all is
        // strictly 500 with a sanitised body.
        ResponseEntity<ApiResult<Void>> resp = handler.handleRuntime(
                new RuntimeException("ERROR: duplicate key value violates unique constraint \"seats_pkey\""));
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, resp.getStatusCode());
        assertNotNull(resp.getBody());
        assertEquals("An unexpected error occurred. Please try again.", resp.getBody().getMessage());
        assertFalse(resp.getBody().getMessage().toLowerCase().contains("duplicate"));
        assertFalse(resp.getBody().getMessage().toLowerCase().contains("constraint"));
    }

    @Test
    void handleRuntime_neverUpgradesNotFoundStringToNotFound() {
        // The old handler mapped any message containing "not found" to a 404.
        // Pin that this is gone — only typed NotFoundException maps to 404 now.
        ResponseEntity<ApiResult<Void>> resp = handler.handleRuntime(
                new RuntimeException("Cache entry not found"));
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, resp.getStatusCode());
    }
}
