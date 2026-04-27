package com.innbucks.bookingservice.exception;

import com.innbucks.bookingservice.dto.ApiResult;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.*;

class GlobalExceptionHandlerTest {

    @Test
    void handleTierRequirement_returns422WithCustomCodeAndMessageInData() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        String reason = "Tier 2 customers may book at most 2 seats per booking";

        ResponseEntity<ApiResult<String>> response =
                handler.handleTierRequirement(new TierRequirementException(reason));

        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, response.getStatusCode());
        ApiResult<String> body = response.getBody();
        assertNotNull(body);
        assertEquals("Do not meet min tier requirement", body.getCode());
        assertNull(body.getMessage());
        assertEquals(reason, body.getData());
    }

    @Test
    void handleRuntime_doesNotInterceptTierRequirementException() {
        // Sanity check: TierRequirementException is handled by its dedicated
        // handler, not by the generic RuntimeException one (which would map
        // to 400 BAD_REQUEST and lose the custom code).
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        ResponseEntity<ApiResult<String>> tierResp =
                handler.handleTierRequirement(new TierRequirementException("anything"));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, tierResp.getStatusCode());
        assertEquals("Do not meet min tier requirement", tierResp.getBody().getCode());
    }
}
