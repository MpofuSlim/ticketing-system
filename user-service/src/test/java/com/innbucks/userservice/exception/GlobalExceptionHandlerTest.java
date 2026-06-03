package com.innbucks.userservice.exception;

import com.innbucks.userservice.dto.ApiResult;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Pins the contract of {@link GlobalExceptionHandler}. Focused on the
 * handlers that need a regression guard outside the controller security
 * tests — currently the ResponseStatusException defence-in-depth handler.
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void responseStatusException_honoursEmbeddedStatusAndReason_notSwallowedByRuntimeCatchAll() {
        // ResponseStatusException extends RuntimeException — without this
        // dedicated handler the @ExceptionHandler(RuntimeException.class)
        // catch-all below would downgrade every deliberate 4xx to a generic
        // 400 with the raw reason text. ShopStaffService still throws
        // ResponseStatusException for 401/403/400; those must reach the
        // client correctly.
        ResponseEntity<ApiResult<Void>> resp = handler.handleResponseStatus(
                new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required"));
        assertEquals(HttpStatus.UNAUTHORIZED, resp.getStatusCode());
        assertNotNull(resp.getBody());
        assertEquals("Authentication required", resp.getBody().getMessage());
    }

    @Test
    void responseStatusException_fallsBackToStatusReasonPhrase_whenReasonIsNull() {
        ResponseEntity<ApiResult<Void>> resp = handler.handleResponseStatus(
                new ResponseStatusException(HttpStatus.FORBIDDEN));
        assertEquals(HttpStatus.FORBIDDEN, resp.getStatusCode());
        assertNotNull(resp.getBody());
        assertEquals("Forbidden", resp.getBody().getMessage());
    }
}
