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

    @Test
    void runtimeCatchAll_replacesRawMessage_withFriendlyText() {
        // Defence-in-depth: an untyped RuntimeException slipping through any
        // service must NOT leak its raw text (could be a stack-trace fragment,
        // table name, etc.). The user sees the friendly fallback; the raw
        // text only reaches the log. The descriptive-error fix means
        // registration paths now throw typed ResponseStatusException instead
        // and bypass this — but the safety net stays.
        ResponseEntity<ApiResult<Void>> resp = handler.handleRuntime(
                new RuntimeException("constraint uk_users_email violated near \"...)"));
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        assertNotNull(resp.getBody());
        assertEquals("We couldn't process your request. Please try again.",
                resp.getBody().getMessage());
    }

    @Test
    void registrationErrors_surfaceTheirDescriptiveReason_notTheCatchAllPlaceholder() {
        // The bug this contract pins: AuthService.register and
        // CustomerService.loadProfile used to throw bare RuntimeException,
        // and the registering user saw "We couldn't process your request"
        // instead of the actual reason (email/phone already registered,
        // service bundle missing, tier prerequisite, etc.). Each typed
        // ResponseStatusException must reach the wire with its descriptive
        // reason intact so the FE can render something the user can act on.
        for (String reason : new String[]{
                "Email already registered",
                "Phone number already registered",
                "Please select at least one service to register for.",
                "We don't recognise the service 'pop-rock'. Available services: [LOYALTY, TICKETING].",
                "This account isn't a customer account.",
                "Please complete tier 1 registration first."
        }) {
            ResponseEntity<ApiResult<Void>> resp = handler.handleResponseStatus(
                    new ResponseStatusException(HttpStatus.BAD_REQUEST, reason));
            assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode(), reason);
            assertNotNull(resp.getBody(), reason);
            assertEquals(reason, resp.getBody().getMessage(),
                    "descriptive registration error must reach the wire verbatim");
        }
    }
}
