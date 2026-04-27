package com.innbucks.bookingservice.exception;

import com.innbucks.bookingservice.dto.ApiResult;
import com.innbucks.bookingservice.dto.TierViolationData;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

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
}
