package com.innbucks.seatservice.exception;

import com.innbucks.seatservice.dto.ApiResult;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    @Test
    void notFoundMessage_returns404() {
        RuntimeException ex = new RuntimeException("Seat not found");

        ResponseEntity<ApiResult<Void>> response = handler.handleRuntime(ex);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    @Test
    void otherRuntime_returns400() {
        RuntimeException ex = new RuntimeException("Seat is not available");

        ResponseEntity<ApiResult<Void>> response = handler.handleRuntime(ex);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }
}
